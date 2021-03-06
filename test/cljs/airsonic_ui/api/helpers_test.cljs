(ns airsonic-ui.api.helpers-test
  (:require [cljs.test :refer [deftest testing is]]
            [clojure.string :as str]
            [airsonic-ui.fixtures :as fixtures :refer [responses]]
            [airsonic-ui.api.helpers :as api]))

(defn- url
  "Construct a url with no params"
  [server endpoint]
  (api/url server endpoint {}))

(def fixtures
  {:default-url (url {:server "http://localhost:8080"} "ping")})

(deftest general-url-construction
  (testing "Handles missing slashes"
    (is (true? (str/starts-with? (url {:server "http://localhost:8080"} "ping") "http://localhost:8080/rest/ping")))
    (is (true? (str/starts-with? (url {:server "http://localhost:8080/"} "ping") "http://localhost:8080/rest/ping"))))
  (testing "Should set correct default parameters"
    (is (string? (re-find #"f=json" (fixtures :default-url))))
    (is (string? (re-find #"v=1\.15\.0" (fixtures :default-url))))))

(deftest parameter-encoding
  (testing "Should escape url parameters"
    (let [query "äöüß"
          encoded-str (js/encodeURIComponent query)]
      (is (str/includes? (api/url {:server "http://localhost"} "search3" {:query query}) encoded-str)))))

(deftest variadic-parameters
  (testing "Should append list-like parameters correctly"
    (is (= (count (re-seq #"id=" (api/url {:server "http://localost"} "test" {:id []}))) 0))
    (is (= (count (re-seq #"id=" (api/url {:server "http://localost"} "test" {:id [1]}))) 1))
    (is (= (count (re-seq #"id=" (api/url {:server "http://localost"} "test" {:id (range 10)}))) 10)))
  (testing "Should keep the non-lists"
    (let [mixed (api/url {:server "http://localost"} "test" {:id (range 5) :foo "bar"})]
      (is (some? (re-find #"u=user" (api/url {:server "http://localhost"} "test" {:u "user"}))))
      (is (and (some? (re-find #"foo=bar" mixed))
               (= (count (re-seq #"id=" mixed)) 5))))))

(deftest stream-urls
  (testing "Should construct the url based on a song's id"
    (let [stream-url (api/stream-url {:server "http://localhost"} fixtures/song)]
      (is (str/includes? stream-url (str "id=" (:id fixtures/song))))))
  (testing "Should also work for podcasts"
    (let [stream-url (api/stream-url {:server "http://localhost"} fixtures/podcast-episode)]
      (is (str/includes? stream-url (str "id=" (:streamId fixtures/podcast-episode)))))))

(deftest cover-urls
  (let [album {:coverArt "cover-99999"}]
    (testing "Should construct the url based on an item's cover-id"
      (is (true? (str/includes? (api/cover-url {:server "http://server.tld"} album -1) (str "id=" (:coverArt album))))))
    (testing "Should scale an image to a given size"
      (is (true? (str/includes? (api/cover-url {:server "http://server.tld"} album 48) "size=48"))))))

(deftest response-handling
  (testing "Should unwrap responses"
    (let [response (:ok responses)]
      (is (= (get-in response [:subsonic-response :scanStatus])
             (api/unwrap-response response)))))
  (testing "Should detect errors"
    (is (true? (api/is-error? (:error responses))))
    (is (false? (api/is-error? (:ok responses)))))
  (testing "Should throw an informative error when trying to unwrap an erroneous response"
    (let [error-response (:error responses)]
      (is (thrown? ExceptionInfo (api/unwrap-response error-response)))
      (try
        (api/unwrap-response error-response)
        (catch ExceptionInfo e
          (is (= (get-in error-response [:subsonic-response :error]) (ex-data e))))))))

(deftest error-recognition
  (testing "Should detect error responses"
    (is (true? (api/is-error? (:error responses))))
    (is (true? (api/is-error? (:auth-failure responses)))))
  (testing "Should pass on good responses"
    (is (false? (api/is-error? (:ok responses))))
    (is (false? (api/is-error? (:ping-success responses))))))

(deftest content-type
  (testing "Should detect whether the data we look at represents a song"
    (is (= :content-type/song (api/content-type fixtures/song))))
  (testing "Should detect whether the data we look at represents an artist"
    (is (= :content-type/artist (api/content-type fixtures/artist)))
    (is (= :content-type/artist (api/content-type (dissoc fixtures/artist :coverArt)))))
  (testing "Should detect whether the data we look at represents an album"
    (is (= :content-type/album (api/content-type fixtures/album)))
    (is (= :content-type/album (api/content-type (dissoc fixtures/album :coverArt))))))
