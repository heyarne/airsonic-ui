(ns airsonic-ui.audio.playlist-test
  (:require [cljs.test :refer [deftest testing is]]
            [airsonic-ui.audio.playlist :as playlist]
            [airsonic-ui.utils.helpers :refer [find-where]]
            [airsonic-ui.fixtures :as fixtures]
            [airsonic-ui.test-helpers :as helpers]
            [debux.cs.core :refer-macros [dbg]]))

(enable-console-print!)

(defn- song []
  (hash-map :id (rand-int 9999)
            :coverArt (rand-int 9999)
            :year (+ 1900 (rand-int 118))
            :artist (helpers/rand-str)
            :artistId (rand-int 100000)
            :title (helpers/rand-str)
            :album (helpers/rand-str)))

(defn- song-queue
  "Generates a seq of n different songs"
  [n]
  (let [r-int (atom 0)]
    (with-redefs [rand-int #(mod (swap! r-int inc) %1)]
      (repeatedly n song))))

(def fixture
  {:audio {:current-song fixtures/song
           :playlist (song-queue 20)
           :playback-status fixtures/playback-status}})

(defn- same-song? [a b] (= (:id a) (:id b)))

(deftest playlist-creation
  (testing "Playlist creation"
    (testing "should give us the correct current song"
      (let [queue (song-queue 10)
            start-idx (rand-int 10)]
        (doseq [playback-mode [:playback-mode/linear, :playback-mode/shuffled]
                repeat-mode [:repeat-mode/none, :repeat-mode/single, :repeat-mode/all]]
          (is (same-song? (nth queue start-idx)
                          (-> (playlist/->playlist queue start-idx playback-mode repeat-mode)
                              (playlist/peek)))
              (str playback-mode ", " repeat-mode)))))
    (testing "should give us a playlist with the correct number of tracks"
      (let [queue (song-queue 100)
            start-idx (rand-int 100)]
        (doseq [playback-mode [:playback-mode/linear, :playback-mode/shuffled]
                repeat-mode [:repeat-mode/none, :repeat-mode/single, :repeat-mode/all]]
          (is (= (count queue)
                 (count (playlist/->playlist queue start-idx playback-mode repeat-mode)))
              (str playback-mode ", " repeat-mode)))))))

(deftest changing-playback-mode
  (testing "Changing playback mode"
    (testing "from linear to shuffled"
      (let [queue (song-queue 10)
            start-idx (rand-int 10)
            linear (playlist/->playlist queue start-idx :playback-mode/linear :repeat-mode/node)
            shuffled (playlist/set-playback-mode linear :playback-mode/shuffled)]
        (testing "should re-order the tracks"
          (is (not= (map :order (:queue shuffled)) (map :order (:queue linear)))))
        (testing "should not change the currently playing track"
          (is (same-song? (playlist/peek linear) (playlist/peek shuffled))))
        (testing "should not change the repeat mode"
          (is (= (:repeat-mode shuffled) (:repeat-mode linear)))))
      (testing "from shuffled to linear"
        (let [queue (song-queue 10)
              start-idx (rand-int 10)
              shuffled (playlist/->playlist queue start-idx
                                            :playback-mode/shuffled :repeat-mode/none)
              linear (playlist/set-playback-mode shuffled :playback-mode/linear)]
          (testing "should set the correct order for tracks"
            (is (every? #(apply same-song? %)
                        (interleave (take start-idx (:queue shuffled))
                                    (take start-idx (:queue linear))))
                "before")
            (is (every? #(apply same-song? %)
                        (interleave (drop (inc start-idx) (:queue shuffled))
                                    (drop (inc start-idx) (:queue linear))))
                "after")
            (is (< (:order (first (:queue linear))) (:order (last (:queue linear))))))
          (testing "should not change the currently playing track"
            (is (same-song? (playlist/peek linear) (playlist/peek shuffled))))
          (testing "should not change the repeat mode"
            (is (= (:repeat-mode shuffled) (:repeat-mode linear)))))))))

(deftest chaging-repeat-mode
  (testing "Changing the repeat mode"
    (testing "should not change the playback mode"
      (doseq [playback-mode [:playback-mode/linear, :playback-mode/shuffled]
              repeat-mode [:repeat-mode/none, :repeat-mode/single, :repeat-mode/all]
              next-repeat-mode [:repeat-mode/none, :repeat-mode/single, :repeat-mode/all]]
        (let [playlist (-> (playlist/->playlist (song-queue 1) 0
                                                playback-mode repeat-mode)
                           (playlist/set-repeat-mode next-repeat-mode))]
          (is (= playback-mode (:playback-mode playlist)))
          (is (= next-repeat-mode (:repeat-mode playlist))
              (str "from " repeat-mode " to " next-repeat-mode)))))))

(deftest linear-next-song
  (testing "Should follow the same order as the queue used for creation"
    (doseq [repeat-mode [:repeat-mode/none :repeat-mode/all]]
      (let [queue (song-queue 5)
            playlist (playlist/->playlist queue 0 :playback-mode/linear repeat-mode)]
        (is (same-song? (nth queue 1) (-> (playlist/next-song playlist)
                                          (playlist/peek)))
            (str repeat-mode ", skipped once"))
        (is (same-song? (nth queue 2) (-> (playlist/next-song playlist)
                                          (playlist/next-song)
                                          (playlist/peek)))
            (str repeat-mode ", skipped twice")))))
  (testing "Should go back to the first song when repeat-mode is all and we played the last song")
  (testing "Should always give the same track when repeat-mode is single"
    (let [queue (song-queue 3)
          playing-idx (rand-int 3)
          playlist (playlist/->playlist queue playing-idx
                                        :playback-mode/linear :repeat-mode/single)
          played-back (map playlist/peek (iterate playlist/next-song playlist))]
      (is (same-song? (nth queue playing-idx) (nth played-back 0)))
      (is (same-song? (nth queue playing-idx) (nth played-back 1)))
      (is (same-song? (nth queue playing-idx) (nth played-back 2)))
      (is (same-song? (nth queue playing-idx) (nth played-back 3)) "wrapping around")))
  (testing "Should stop playing at the end of the queue when repeat-mode is none"
    (is (nil? (-> (song-queue 1)
                  (playlist/->playlist 0 :playback-mode/linear :repeat-mode/none)
                  (playlist/next-song)
                  (playlist/peek))))))

(deftest shuffled-next-song
  (testing "Should play every track once when called for the entire queue"
    (with-redefs [shuffle reverse]
      (doseq [repeat-mode '(:repeat-mode/none :repeat-mode/all)]
        (let [length 10
              playlist (playlist/->playlist (song-queue length) 9
                                            :playback-mode/shuffled repeat-mode)
              played-tracks (->> (iterate playlist/next-song playlist)
                                 (map playlist/peek)
                                 (take length))]
          (is (= (count played-tracks) (count (set played-tracks)))
              (str repeat-mode))))))
  (testing "Should re-shuffle the playlist when wrapping around and repeat-mode is all"
    (let [playlist (playlist/->playlist (song-queue 100) 0 :playback-mode/shuffled :repeat-mode/all)
          [last-idx _] (find-where #(= (:order %) 99) (:queue playlist))]
      (is (not= (map :order (:queue playlist))
                (map :order (:queue (-> (playlist/set-current-song playlist last-idx)
                                        (playlist/next-song))))))))
  (testing "Should always give the same track when repeat-mode is single"
    (let [queue (song-queue 3)
          playing-idx (rand-int 3)
          playlist (playlist/->playlist queue playing-idx
                                        :playback-mode/shuffled :repeat-mode/single)
          played-back (map playlist/peek (iterate playlist/next-song playlist))]
      (is (same-song? (nth queue playing-idx) (nth played-back 0)))
      (is (same-song? (nth queue playing-idx) (nth played-back 1)))
      (is (same-song? (nth queue playing-idx) (nth played-back 2)))
      (is (same-song? (nth queue playing-idx) (nth played-back 3)) "wrapping around")))
  (testing "Should stop playing at the end of the queue when repeat-mode is none"
    (is (nil? (-> (song-queue 1)
                  (playlist/->playlist 0 :playback-mode/linear :repeat-mode/none)
                  (playlist/next-song)
                  (playlist/peek))))))

(deftest linear-previous-song
  (testing "Should always give the same track when repeat-mode is single"
    (let [queue (song-queue 3)
          playing-idx (rand-int 3)
          playlist (playlist/->playlist queue playing-idx
                                        :playback-mode/linear :repeat-mode/single)
          played-back (map playlist/peek (iterate playlist/next-song playlist))]
      (is (same-song? (nth queue playing-idx) (nth played-back 0)))
      (is (same-song? (nth queue playing-idx) (nth played-back 1)))
      (is (same-song? (nth queue playing-idx) (nth played-back 2)))
      (is (same-song? (nth queue playing-idx) (nth played-back 3)) "wrapping around")))
  (testing "Should keep the linear order when repeat-mode is not single"
    (doseq [repeat-mode '(:repeat-mode/none :repeat-mode/all)]
      (let [queue (song-queue 3)
            playlist (playlist/->playlist queue 0 :playback-mode/linear repeat-mode)]
        (is (same-song? (nth queue 1) (-> (playlist/next-song playlist)
                                          (playlist/next-song)
                                          (playlist/previous-song)
                                          (playlist/peek)))))))
  (testing "Should repeatedly give the first song when repeat-mode is none"
    (let [queue (song-queue 3)
          playlist (playlist/->playlist queue 0 :playback-mode/linear :repeat-mode/none)]
      (is (same-song? (first queue) (-> (playlist/previous-song playlist)
                                        (playlist/peek))))))
  (testing "Should wrap around to last song when repeat-mode is all"
    (let [queue (song-queue 3)
          playlist (playlist/->playlist queue 0 :playback-mode/linear :repeat-mode/all)]
      (is (same-song? (last queue) (-> (playlist/previous-song playlist)
                                       (playlist/peek)))))))

(deftest shuffled-previous-song
  (with-redefs [shuffle reverse]
    (testing "Should always give the same track when repeat-mode is single"
      (let [queue (song-queue 3)
            playing-idx (rand-int 3)
            playlist (playlist/->playlist queue playing-idx
                                          :playback-mode/shuffled :repeat-mode/single)
            played-back (map playlist/peek (iterate playlist/next-song playlist))]
        (is (same-song? (nth queue playing-idx) (nth played-back 0)))
        (is (same-song? (nth queue playing-idx) (nth played-back 1)))
        (is (same-song? (nth queue playing-idx) (nth played-back 2)))
        (is (same-song? (nth queue playing-idx) (nth played-back 3)) "wrapping around")))
    (testing "Should keep the playing order when repeat-mode is not single"
      (doseq [repeat-mode '(:repeat-mode/none :repeat-mode/all)]
        (let [queue (song-queue 3)
              playlist' (playlist/->playlist queue 0 :playback-mode/shuffled repeat-mode)
              [first-idx _] (find-where #(= (:order %) 0) (:queue playlist'))
              playlist (playlist/set-current-song playlist' first-idx)]
          (is (same-song? (playlist/peek playlist)
                          (-> playlist
                              (playlist/next-song)
                              (playlist/previous-song)
                              (playlist/peek)))
              (str "for repeat mode " repeat-mode))
          (is (same-song? (-> (playlist/next-song playlist)
                              (playlist/peek))
                          (-> (playlist/next-song playlist)
                              (playlist/next-song)
                              (playlist/previous-song)
                              (playlist/peek)))
                (str "for repeat mode " repeat-mode)))))
    (testing "Should re-shuffle when repeat-mode is all and we pass the start"
      (with-redefs [shuffle identity]
        (let [playlist (playlist/->playlist (song-queue 2) 0 :playback-mode/shuffled :repeat-mode/all)
              playlist' (with-redefs [shuffle reverse]
                          (playlist/previous-song playlist))]
          (is (not= (map :order (:queue playlist)) (map :order (:queue playlist')))))))))

(deftest set-current-song
  (testing "Should correctly set the new song"
    (let [queue (song-queue 3)
          playlist (playlist/->playlist queue 0 :playback-mode/shuffled :repeat-mode/single)
          current-track (first queue)
          next-track (-> (playlist/set-current-song playlist 1)
                         (playlist/peek))]
      (is (not (nil? next-track)))
      (is (not (same-song? current-track next-track))))))

(deftest enqueue-last
  (testing "Should make sure the song is played last"
    (doseq [playback-mode '(:playback-mode/linear :playback-mode/shuffled)
            repeat-mode '(:repeat-mode/none :repeat-mode/all)]
      (let [length 5, queue (song-queue length)
            playlist (with-redefs [shuffle identity]
                       (playlist/->playlist queue 0 playback-mode repeat-mode))
            played-back (->> (iterate playlist/next-song playlist)
                             (take (dec length))
                             (map #(:id (playlist/peek %)))
                             (set))
            to-enqueue (song)
            playlist' (playlist/enqueue-last playlist to-enqueue)]
        (is (nil? (played-back (-> (->> (iterate playlist/next-song playlist')
                                        (map playlist/peek))
                                   (nth length)
                                   (:id))))
            (str "for " playback-mode ", " repeat-mode)))))
  (testing "Should not change the order of the songs already in queue"
    (doseq [playback-mode '(:playback-mode/linear :playback-mode/shuffled)
            repeat-mode '(:repeat-mode/none :repeat-mode/all)]
      (let [length 5, queue (song-queue length)
            playlist (playlist/->playlist queue 0 playback-mode repeat-mode)
            ;; FIXME: Playlists should just start with order 0, basta
            [first-song-idx _] (find-where #(= 0 (:order %)) (:queue playlist))
            playlist (playlist/set-current-song playlist first-song-idx)
            played-back-songs (fn played-back-songs [playlist]
                                (->> (iterate playlist/next-song playlist)
                                     (take length)
                                     (map playlist/peek)
                                     (map :order)))
            played-back (played-back-songs playlist)
            played-back' (played-back-songs (playlist/enqueue-last playlist (song)))]
        (is (= played-back played-back')
            (str "for " playback-mode ", " repeat-mode))))))

(deftest enqueue-next
  (testing "Should play the song after the currently playing song"
    (doseq [playback-mode '(:playback-mode/linear :playback-mode/shuffled)
            repeat-mode '(:repeat-mode/none :repeat-mode/all)]
      (let [length 5, queue (song-queue length)
            playlist (playlist/->playlist queue 0 playback-mode repeat-mode)
            next-song (song)]
        (is (same-song? next-song (-> (playlist/enqueue-next playlist next-song)
                                      (playlist/next-song)
                                    (playlist/peek))))))))
