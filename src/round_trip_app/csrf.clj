(ns round-trip-app.csrf
  (:require [round-trip-app.strategy :refer [Strategy]]
            [crypto.random :as random]
            [crypto.equality :as crypto]
            [ring.middleware.cookies :refer [cookies-request cookies-response]]
            [clojure.string :as str]
            [pandect.algo.sha256 :refer [sha256-hmac]]))


(defn read-tok [request]
  (get-in request [:form-params "csrf-token"]))


(defn- csrf-token [request]
  (let [cookied-request [cookies-request request]]
    (get-in cookied-request [:cookies "x-csrf-token" :value])))


(defn generate-csrf-token []
  (let [base64-str (random/base64 60)
          enc-base64-str (sha256-hmac base64-str "unifize-secret-key")]
      (str base64-str "." enc-base64-str)))


(defn- get-csrf-tokens [cookie-header hidden-html]
  (let [cookie-header-head (first (str/split cookie-header #"\."))
        cookie-header-tail (last (str/split cookie-header #"\."))
        hidden-html-head (first (str/split hidden-html #"\."))
        hidden-html-tail (last (str/split hidden-html #"\."))]
    [cookie-header-head cookie-header-tail hidden-html-head hidden-html-tail]))


(defn- valid-tokens? [cookie-header-token hidden-html-token]
  (let [[cookie-header-csrf-head
         cookie-header-csrf-tail
         hidden-html-csrf-head
         hidden-html-csrf-tail] (get-csrf-tokens cookie-header-token hidden-html-token)]
    (and (= cookie-header-token hidden-html-token)
         (crypto/eq? cookie-header-csrf-head hidden-html-csrf-head)
         (= cookie-header-csrf-tail hidden-html-csrf-tail)
         (= cookie-header-csrf-tail (sha256-hmac hidden-html-csrf-head "unifize-secret-key")))))


(deftype DoubleSubmitCookieStrategy [] 
  Strategy 
  (get-token [this request]
    (println "request: " request)
    (or (csrf-token request) 
        (generate-csrf-token)))

  (valid-token? [_ request token]
    (let [cookie-token (csrf-token request)]
      (and token
           cookie-token
           (valid-tokens? cookie-token token))))
  
  (write-token [this request response token]
               (let [curr-token (csrf-token request)]
                 (if (= curr-token token)
                   response
                   (cookies-response (-> response
                                         (update-in [:cookies] assoc :x-csrf-token token)))))))


(defn double-submit-cookie-strategy []
  (->DoubleSubmitCookieStrategy))

