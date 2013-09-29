(defproject followhackers "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [compojure "1.1.5"]
                 [clj-time "0.6.0"]
                 [ring "1.2.0"]
                 [hiccup "1.0.2"]
                 [http-kit "2.1.10"]
                 [cheshire "5.2.0"]
                 [com.draines/postal "1.11.0"]]
  :plugins [[lein-ring "0.8.7"]]
  :ring  {:handler followhackers.core/app})
