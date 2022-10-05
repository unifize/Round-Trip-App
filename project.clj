(defproject round-trip-app "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [com.google.cloud/google-cloud-storage "1.113.15"]
                 [com.google.firebase/firebase-admin "7.1.1"]
                 [com.google.cloud/google-cloud-firestore "2.2.6"]
                 [com.google.cloud/google-cloud-logging "2.2.1"]
                 [com.google.cloud/google-cloud-errorreporting "0.120.40-beta"]
                 [hiccup "1.0.5"]
                 [ring "1.9.6"]
                 [ring/ring-anti-forgery "1.3.0"]
                 [metosin/muuntaja "0.6.7"]
                 [metosin/reitit "0.5.11"]
                 [metosin/ring-http-response "0.9.1"]
                 [clj-http "3.12.3"]
                 [hickory "0.7.1"]
                 [pandect "1.0.2"]
                 [cheshire "5.10.0"]]
  :main ^:skip-aot round-trip-app.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
