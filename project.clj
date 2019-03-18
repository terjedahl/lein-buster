(defproject no.terjedahl/lein-buster "0.2.0"

  :description "Generate fingerprinted files from your static asset that are suitable for use in browser cache-busting."

  :url "https://github.com/terjedahl/lein-buster"

  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[cheshire "5.5.0" :exclusions [org.clojure/clojure]]]

  :eval-in-leiningen true

  :deploy-repositories [["snapshots" :clojars]
                        ["releases" :clojars]])
