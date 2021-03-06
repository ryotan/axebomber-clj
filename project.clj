(defproject net.unit8/axebomber-clj "0.1.1-SNAPSHOT"
  :description "The generator for MS-Excel grid sheet"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.apache.poi/poi "3.10.1"]
                 [org.apache.poi/poi-ooxml "3.10.1"]
                 [clj-time "0.8.0"]
                 [hiccup "1.0.5"]]
  :profiles {:dev
              {:dependencies [[midje "1.6.3"]]
               :plugins [[lein-midje "3.1.1"]]}})

