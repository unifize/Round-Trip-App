(ns round-trip-app.core
  (:import [com.google.firebase FirebaseOptions$Builder]
           [com.google.firebase.auth SessionCookieOptions]
           [com.google.firebase FirebaseApp]
           [com.google.firebase.auth FirebaseAuth]
           [com.google.auth.oauth2 GoogleCredentials]
           [com.google.auth.oauth2 ComputeEngineCredentials])
  (:require [hiccup.core :refer :all]
            [hiccup.page :refer :all]
            [ring.adapter.jetty :as jetty]
            [reitit.ring :as reitit]
            [muuntaja.middleware :as muuntaja]
            [ring.util.http-response :as response]
            [ring.util.response :refer [redirect]]
            [taoensso.timbre :as timbre]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cookies :refer [cookies-response cookies-request]]
            [cheshire.core :as json]
            [clojure.java.io :as io :refer [input-stream]]
            [hickory.zip :as hickory]
            [hickory.core :as hickoryc]
            [clojure.zip :as zip]
            [clojure.string :as str]
            [clj-http.client :as http])
  (:gen-class))

(defn get-google-credentials
  ([service-account]
   (if (str/blank? service-account)
     (ComputeEngineCredentials/create)
     (GoogleCredentials/fromStream (input-stream service-account))))
  ([]
   (get-google-credentials "/Users/namit2711/Desktop/Unifize/auth/session-cookie.json")))


(defn build-options
  "Option builder"
  [{:keys [google-credentials]}]
  (let [option-builder (FirebaseOptions$Builder.)]
    (doto option-builder
      (.setCredentials google-credentials)
      (.setProjectId "session-cookie-firebase"))
    (.build option-builder)))


(FirebaseApp/initializeApp (build-options {:google-credentials (get-google-credentials)}))


(defn get-exp-in-millis [time-unit time-value]
  (cond
    (= time-unit "weeks") (* 604800000 time-value)
    (= time-unit "days") (* 86400000 time-value)
    (= time-unit "hours") (* 3600000 time-value)
    (= time-unit "minutes") (* 60000 time-value)
    (= time-unit "seconds") (* 1000 time-value)))


(defn create-session-cookie [token time-unit time-value]
  (let [session-cookie-options (SessionCookieOptions/builder)]
    (doto session-cookie-options
      (.setExpiresIn (get-exp-in-millis time-unit time-value)))
    (.createSessionCookie (FirebaseAuth/getInstance) token (.build session-cookie-options))))


(defn verify-cookie [cookie]
  (let [fauth (FirebaseAuth/getInstance)]
    (try
      (.verifySessionCookie fauth cookie)
      true
      (catch Exception e
        (println "Exception" e)
        false))))


(defn firebase-sign-in [email password]
  (try
    (let [response (-> (http/post "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword"
                                  {:query-params {:key "key"}
                                   :form-params {:email email
                                                 :password password
                                                 :returnSecureToken true}})
                       :body
                       json/parse-string)]
          (response "idToken"))
  (catch Exception _
    nil)))


(defn login-page [_]
  (response/ok
   (html [:html
          [:head
           [:title "Login"]]
          [:body
           [:h1 "Login"]
           [:form {:action "/login" :method "post"}
            [:input {:type "text" :name "email"}]
            [:input {:type "password" :name "password"}]
            [:input {:type "submit" :value "Login"}]]]])))


(defn home-page [_]
  (response/ok
   (html5 [:head [:title "Home"]]
          [:body
           [:h1 "Home"]
           [:p "Welcome to the home page."]
           [:form {:action "/check-strength" :method "post"}
            [:input {:type "hidden" :name "csrf-token"}]
            [:input {:type "text" :name "password"}]
            [:input {:type "submit" :value "Check Password Strength"}]]
           [:a {:href "/logout"} "Logout"]])))


(defn html->hiccup [html]
  (hickoryc/as-hiccup (hickoryc/parse html)))


(defn hiccup->zip [hiccup]
  (hickory/hiccup-zip hiccup))


(defn is-csrf-input-tag? [tag]
  (and (= (first tag) :input)
       (= (:type (second tag)) "hidden")
       (= (:name (second tag)) "csrf-token")))


(defn add-csrf-token [zipped-html csrf-token]
  (loop [zip zipped-html]
    (if (zip/end? zip)
      zip
      (if (is-csrf-input-tag? (zip/node zip))
        (recur (zip/next
                (zip/replace zip [:input
                                  {:type "hidden"
                                   :name "csrf-token"
                                   :value csrf-token}])))
        (recur (zip/next zip))))))


(defn add-csrf-token-to-html [html-code csrf-token]
  (let [zipped-hiccup (-> html-code
                          html->hiccup
                          hiccup->zip)
        hiccup-with-csrf (add-csrf-token zipped-hiccup csrf-token)]
    (html (zip/node hiccup-with-csrf))))


(defn csrf-token-adder [handler]
  (fn [request]
    (let [response (handler request)]
      (if (= (:status response) 200)
        (let [csrf-token "xyz"
              html (add-csrf-token-to-html (:body response) csrf-token)]
          (assoc response :body html))
        response))))


(defn anti-forgery [handler]
  (fn [request]
    (let [cookied-request (cookies-request request)
          session-cookie (get-in cookied-request [:cookies "session-cookie" :value])
          csrf-token (get-in request [:form-params "csrf-token"])]
      (if (or (nil? session-cookie) 
              (not= csrf-token "xyz") ;; replace by making the database call to get and verify the csrf token
              (not (verify-cookie session-cookie)))
        (redirect "/")
        (handler request)))))


(defn check-strength [request]
  (let [password (get-in request [:form-params "password"])]
    (response/ok (json/generate-string {:strength (count password)}))))


(defn add-cookie-header [response id-token]
  (-> (assoc response :cookies {:session-cookie (create-session-cookie id-token "days" 5)})
      cookies-response))


(defn login-handler [request]
  (let [params (:form-params request)
        email (params "email")
        password (params "password")]
    (if-let [id-token (firebase-sign-in email password)]
      (add-cookie-header (redirect "/home") id-token)
      (redirect "/"))))


(def routes
  [["/" {:get login-page}]
   ["/login" {:post login-handler}]
   ["/home" {:get {:handler home-page
                   :middleware [csrf-token-adder]}}]
   ["/check-strength" {:post {:handler check-strength
                              :middleware [anti-forgery]}}]])


(defn wrap-formats [handler]
  (-> handler
      (muuntaja/wrap-format)))


(def handler
  (reitit/ring-handler
   (reitit/router routes)
   (reitit/create-default-handler
    {:not-found
     (constantly (response/not-found "404 - Page not found"))
     :method-not-allowed
     (constantly (response/method-not-allowed "405 - Not allowed"))
     :not-acceptable
     (constantly (response/not-acceptable "406 - Not acceptable"))})))


(defn -main []
  (jetty/run-jetty (-> #'handler
                       wrap-formats
                       wrap-params
                       wrap-reload)
                   {:port 3000
                    :join? true}))
