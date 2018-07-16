(ns airsonic-ui.events
  (:require [re-frame.core :as re-frame]
            [ajax.core :as ajax]
            [airsonic-ui.routes :as routes]
            [airsonic-ui.db :as db]
            [airsonic-ui.utils.api :as api]
            [day8.re-frame.tracing :refer-macros [fn-traced defn-traced]])) ; <- useful to debug handlers

(re-frame/reg-fx
 ;; a simple effect to keep println statements out of our event handlers
 :log
 (fn [params]
   (apply println params)))

(defn noop
  "An event handler that can be used for clarity; doesn't do anything, but might
  give a name to an event"
  [cofx _] cofx)

;; ---
;; app boot flow
;; * restoring a previous session
;; * initializing the router
;; * sending out the appropriate requests
;; ---

(re-frame/reg-event-fx
 ::initialize-app
 (fn [_]
   {:db db/default-db
    :dispatch [:init-flow/restore-previous-session]}))

(defn restore-previous-session
  "See comment above for different steps; what's important here is that we check
  for a previous session before anything else, otherwise we might run into auth
  troubles with our router."
  [{:keys [db store]} _]
  (let [credentials (:credentials store)]
    {:dispatch-n [(if credentials
                    [:init-flow/credentials-found credentials]
                    [:init-flow/credentials-not-found])]
     :routes/start-routing nil}))

(re-frame/reg-event-fx
 :init-flow/restore-previous-session
 [(re-frame/inject-cofx :store)]
 restore-previous-session)

(defn credentials-found [_ [_ {:keys [u p server]}]]
  {:dispatch [:credentials/verification-request u p server]})

(re-frame/reg-event-fx :init-flow/credentials-found credentials-found)

;; we don't do anything special here, it's just for the sake of clarity

(defn credentials-not-found
  [cofx _]
  (assoc-in cofx [:db :credentials] :credentials/not-found))

(re-frame/reg-event-fx :init-flow/credentials-not-found credentials-not-found)

;; ---
;; auth logic
;; ---

(defn credentials-verification-request
  "Tries to authenticate a user by pinging the server with credentials, saving
  them when the request was successful. Bypasses the request when a user saved
  their credentials."
  [_ [_ user pass server]]
  {:http-xhrio {:method :get
                :uri (api/url server "ping" {:u user :p pass})
                :response-format (ajax/json-response-format {:keywords? true})
                :on-success [:credentials/verification-response user pass server]
                :on-failure [:credentials/verification-failure]}})

(re-frame/reg-event-fx :credentials/verification-request credentials-verification-request)

(defn credentials-verification-response
  "Since we don't get real status codes, we have to look into the server's
  response and see whether we actually sent the correct credentials"
  [fx [_ user pass server response]]
  {:dispatch (if (api/is-error? response)
               [:credentials/verification-failure response]
               [:credentials/verified user pass server])})

(re-frame/reg-event-fx :credentials/verification-response credentials-verification-response)

(defn credentials-verification-failure [fx [_ response]]
  (-> (assoc-in fx [:db :credentials] :credentials/verification-failure)
      (assoc :dispatch [:notification/show :error (api/error-msg (api/->exception response))])))

(re-frame/reg-event-fx :credentials/verification-failure credentials-verification-failure)

(defn credentials-verified
  "Gets called after the server indicates that the credentials entered by a user
  are correct (see `credentials-verification-request`)"
  [{:keys [db]} [_ user pass server]]
  (let [credentials {:u user :p pass :server server}]
    {:routes/set-credentials credentials
     :store {:credentials credentials}
     :db (assoc db :credentials credentials)
     :dispatch [::logged-in]}))

(re-frame/reg-event-fx :credentials/verified credentials-verified)

;; TODO: We have to find another solution for this once we have routes that
;; don't require a login but have the bottom controls

(re-frame/reg-fx
 :show-nav-bar
 (fn [_]
   (.. js/document -documentElement -classList (add "has-navbar-fixed-bottom"))))


(defn logged-in
  [cofx _]
  (let [redirect (or (get-in cofx [:routes/from-query-param :redirect]) [::routes/main])]
    {:routes/navigate redirect
     :show-nav-bar nil}))

(re-frame/reg-event-fx
 ::logged-in
 [(re-frame/inject-cofx :routes/from-query-param :redirect)]
 logged-in)

(defn logout
  "Clears all credentials and redirects the user to the login page"
  [cofx [_ & args]]
  (let [args (apply hash-map args)]
    {:routes/navigate (if-let [redirect (:redirect-to args)]
                        [::routes/login {} {:redirect (routes/encode-route redirect)}]
                        [::routes/login])
     :routes/unset-credentials nil
     :store nil
     :db (merge (:db cofx) db/default-db {:credentials :credentials/logged-out})}))

(re-frame/reg-event-fx ::logout logout)

;; ---
;; api interaction
;; ---

(defn- api-url [db endpoint params]
  (let [creds (:credentials db)]
    (api/url (:server creds) endpoint (merge params (select-keys creds [:u :p])))))

(defn api-request [{:keys [db]} [_ endpoint params]]
  {:http-xhrio {:method :get
                :uri (api-url db endpoint params)
                :response-format (ajax/json-response-format {:keywords? true})
                :on-success [:api/good-response]
                :on-failure [:api/bad-response]}})

(re-frame/reg-event-fx :api/request api-request)

(defn good-api-response [fx [_ response]]
  (try
    (assoc-in fx [:db :response] (api/unwrap-response response))
    (catch ExceptionInfo e
      {:dispatch [:notification/show :error (api/error-msg e)]})))

(re-frame/reg-event-fx :api/good-response good-api-response)

(defn bad-api-response [db event]
  {:log ["API call gone bad; are CORS headers missing? check for :status 0" event]
   :dispatch [:notification/show :error "Communication with server failed. Check browser logs for details."]})

(re-frame/reg-event-fx :api/bad-response bad-api-response)

;; ---
;; musique
;; ---

; TODO: Make play, next and previous a bit prettier and more DRY

(defn- song-url [db song]
  (let [creds (:credentials db)]
    (api/song-url (:server creds) (select-keys creds [:u :p]) song)))

(re-frame/reg-event-fx
 ; sets up the db, starts to play a song and adds the rest to a playlist
 ::play-songs
 (fn [{:keys [db]} [_ songs song]]
   {:play-song (song-url db song)
    :db (-> db
            (assoc-in [:currently-playing :item] song)
            (assoc-in [:currently-playing :playlist] songs))}))

(re-frame/reg-event-fx
 ::next-song
 (fn [{:keys [db]} _]
   (let [playlist (-> db :currently-playing :playlist)
         current (-> db :currently-playing :item)
         next (first (rest (drop-while #(not= % current) playlist)))]
     (when next
       {:play-song (song-url db next)
        :db (assoc-in db [:currently-playing :item] next)}))))

(re-frame/reg-event-fx
 ::previous-song
 (fn [{:keys [db]} _]
   (let [playlist (-> db :currently-playing :playlist)
         current (-> db :currently-playing :item)
         previous (last (take-while #(not= % current) playlist))]
     (when previous
       {:play-song (song-url db previous)
        :db (assoc-in db [:currently-playing :item] previous)}))))

(re-frame/reg-event-fx
 ::toggle-play-pause
 (fn [_ _]
   {:toggle-play-pause nil}))

(re-frame/reg-event-db
 :audio/update
 (fn [db [_ status]]
   ; we receive this from the player once it's playing
   (assoc-in db [:currently-playing :status] status)))

;; ---
;; routing
;; ---

(re-frame/reg-event-fx
 :routes/navigation
 (fn [{:keys [db]} [_ route params query]]
   ;; all the naviagation logic is in routes.cljs; all we need to do here
   ;; is say what actually happens once we've navigated succesfully
   {:db (assoc db :current-route [route params query])
    :dispatch (routes/route-data route params query)}))

(re-frame/reg-event-fx
 :routes/unauthorized
 [(re-frame/inject-cofx :routes/current-route)]
 (fn [{:routes/keys [current-route]} _]
   {:dispatch [::logout :redirect-to current-route]}))

;; ---
;; user messages
;; ---

(def notification-duration
  {:info 2500
   :error 10000})

(defn show-notification
  "Displays an informative message to the user"
  [fx [_ level message]]
  (let [id (.now js/performance)
        hide-later (fn [level]
                     [{:ms (get notification-duration level)
                       :dispatch [:notification/hide id]}])]
    (if (nil? message)
      (let [message level
            level :info]
        (-> (assoc-in fx [:db :notifications id] {:level level
                                                  :message message})
            (assoc :dispatch-later (hide-later level))))
      (-> (assoc-in fx [:db :notifications id] {:level level
                                                :message message})
          (assoc :dispatch-later (hide-later level))))))

(re-frame/reg-event-fx :notification/show show-notification)

(defn hide-notification
  [db [_ notification-id]]
  (update db :notifications dissoc notification-id))

(re-frame/reg-event-db :notification/hide hide-notification)
