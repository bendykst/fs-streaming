(require 'leiningen.exec)
(leiningen.exec/deps '[[org.clojure/clojure "1.7.0"]
                       [korma "0.4.2"]
                       [org.xerial/sqlite-jdbc "3.8.11.2"]
                       [adamwynne/feedparser-clj "0.5.2"]
                       [clj-http "2.0.0"]
                       [throttler "1.0.0"]])

(require '[feedparser-clj.core :as feedparser]
         '[korma.db :as kdb]
         '[korma.core :as kcore]
         '[clojure.java.io :as io]
         '[clojure.pprint :refer :all]
         '[clj-http.client :as http]
         '[throttler.core :refer [throttle-fn]])

(def sqll (kdb/sqlite3 {:db "episodes.db"}))
(kdb/defdb db sqll)
(kcore/defentity episodes)

(def service-aliases
  {"Netflix Instant" "NFLX"
   "Amazon Prime Instant" "AMZN"
   "Crackle" "CRKL"
   "Epix" "EPIX"
   "Fandor" "FNDR"
   "Hulu Plus" "HULU"
   "Snagfilms" "SNAG"
   "XFINITY Streampix" "XFTY"})

(defn assoc-streaming-options [ep]
  (println "Getting options for" (:media_title ep))
  (flush)
  (let [resp (http/get
               "http://www.canistream.it/services/query"
               {:query-params {:movieId (:cisi_id ep)
                               :attributes true
                               :mediaType "streaming"}
                :accept :json
                :as :json})]
    (->> resp
         :body
         vals
         (map :friendlyName)
         (map #(or (service-aliases %) %))
         set
         (assoc ep :streaming_options))))

(def throttled-options (throttle-fn assoc-streaming-options 1 :second 4))

(defn format-results [eps]
  (let [streaming-sites (->> eps
                             (mapcat :streaming_options)
                             set
                             (remove #{"SNAG" "FNDR"})
                             (sort-by
                               #(condp = %
                                  "NFLX" "AAA"
                                  "HULU" "AAB"
                                  "AMZN" "AAC"
                                  %)))
        header-row (list
                     "Episode"
                      (for
                        [site streaming-sites]
                        (str " | " site))
                     "\n")
        body-rows (for [ep eps]
                    (list
                      (:episode_title ep)
                      (for [site streaming-sites]
                        (if
                          ((:streaming_options ep) site)
                          (str "|**" site "**")
                          "|"))
                      "\n"))]
    (apply str
      (flatten
        (list
          header-row
          ":---"
          (repeat (count streaming-sites) "|:---:")
          "\n"
          body-rows)))))

(let [episode-data (kcore/select episodes
                     (kcore/where {:ignore 0}))]
  (->> episode-data
       (map throttled-options)
       (remove (comp empty? :streaming_options))
       (sort-by :episode_id)
       format-results
       print))
