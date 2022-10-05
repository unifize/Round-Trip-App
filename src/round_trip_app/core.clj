(ns round-trip-app.core
  (:import [com.google.firebase FirebaseOptions$Builder]
           [com.google.firebase.auth SessionCookieOptions]
           [com.google.firebase FirebaseApp]
           [com.google.firebase.auth FirebaseAuth]
           [com.google.auth.oauth2 GoogleCredentials]
           [com.google.auth.oauth2 ComputeEngineCredentials])
  (:require [hiccup.core :refer :all]
            [hiccup.page :refer :all]
            [round-trip-app.csrf :refer [double-submit-cookie-strategy]]
            [ring.adapter.jetty :as jetty]
            [reitit.ring :as reitit]
            [muuntaja.middleware :as muuntaja]
            [ring.util.http-response :as response]
            [ring.util.response :refer [redirect]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.anti-forgery :as af]
            [ring.util.anti-forgery :as afu]
            [cheshire.core :as json]
            [clojure.java.io :as io :refer [input-stream]]
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
    (-> (http/post
         "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword"
         {:query-params {:key "<API_KEY>"}
          :form-params {:email email
                        :password password
                        :returnSecureToken true}})
        :body
        json/parse-string
        (get "idToken"))
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
            (afu/anti-forgery-field)
            [:input {:type "text" :name "password"}]
            [:input {:type "submit" :value "Check Password Strength"}]]
           [:a {:href "/logout"} "Logout"]])))


(defn strength-checker [request]
  (let [password (get-in request [:form-params "password"])]
    (response/ok
     (html5 [:head [:title "Strength Checker"]]
            [:body
             [:h1 "Strength Checker"]
             [:p "Strength of the password is " (count password)]
             [:a {:href "/home"} "Home"]]))))


(defn session-cookie-check [handler]
  (fn [request]
    (let [session-cookie (get-in request [:cookies "session-cookie" :value])]
      (if (not (verify-cookie session-cookie))
        (redirect "/")
        (handler request)))))


(defn add-cookie-header [response id-token]
  (-> response
      (assoc :cookies {:session-cookie (create-session-cookie
                                        id-token
                                        "days"
                                        5)})))


(defn login [request]
  (let [params (:form-params request)
        email (params "email")
        password (params "password")]
    (if-let [id-token (firebase-sign-in email password)]
      (add-cookie-header (redirect "/home") id-token)
      (redirect "/"))))


(defn logout [_]
  (-> (redirect "/")
      (assoc :cookies {:session-cookie {:value ""
                                        :max-age 0}
                       :x-csrf-token {:value ""
                                      :max-age 0}})))


(def routes
  [["/" {:get login-page}]
   ["/login" {:post login}]
   ["/home" {:get (session-cookie-check
                   (af/wrap-anti-forgery
                    home-page
                    {:strategy (double-submit-cookie-strategy)}))}]
   ["/check-strength" {:post (session-cookie-check
                              (af/wrap-anti-forgery
                               strength-checker
                               {:strategy (double-submit-cookie-strategy)}))}]
   ["/logout" {:get (session-cookie-check logout)}]])


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
                       wrap-cookies
                       wrap-reload)
                   {:port 3000
                    :join? true}))
