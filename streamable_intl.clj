(require 'leiningen.exec)
(leiningen.exec/deps '[[org.clojure/clojure "1.7.0"]
                       [environ "1.0.1"]
                       [korma "0.4.2"]
                       [org.xerial/sqlite-jdbc "3.8.11.2"]
                       [adamwynne/feedparser-clj "0.5.2"]
                       [clj-http "2.0.0"]
                       [throttler "1.0.0"]])

(require '[feedparser-clj.core :as feedparser]
         '[environ.core :refer [env]]
         '[korma.db :as kdb]
         '[korma.core :as kcore]
         '[clojure.java.io :as io]
         '[clojure.pprint :refer :all]
         '[clj-http.client :as http]
         '[throttler.core :refer [throttle-fn]])

(def sqll (kdb/sqlite3 {:db "episodes.db"}))
(kdb/defdb db sqll)
(kcore/defentity episodes)

(def unogs-key (env :unogs-api-key))

(when
  (nil? unogs-key)
  (throw (Exception. "UNOGS API key not found.")))

(def countries-of-interest
  #{"GB" "FR" "AU" "CA" "DE" "ZA"})

(defn assoc-streaming-options [ep]
  (println "Getting options for" (:media_title ep))
  (flush)
  (let [resp (http/get
               "http://unogs.com/cgi-bin/nf.cgi?"
               {:query-params {:t "loadvideo"
                               :q (:netflix_id ep)
                               :api unogs-key}
                :accept :json
                :as :json})]
    (some->> resp
             :body
             :RESULT
             :country
             (map second)
             (map clojure.string/upper-case)
             set
             (clojure.set/intersection countries-of-interest)
             (assoc ep :streaming_options))))

(def throttled-options (throttle-fn assoc-streaming-options 1 :second))

(defn format-results [eps]
  (let [countries (->> eps
                       (mapcat :streaming_options)
                       set
                       (sort-by
                         #(condp = %
                            "CA" "AAA"
                            "GB" "AAB"
                            %)))]
    (apply str
      (flatten
        (list
          "Episode"
          (for [country countries] (str " | " country))
          "\n:---"
          (repeat (count countries) "|:---:")
          "\n"
          (for [ep eps]
            (list
              (:episode_title ep)
              (for [country countries]
                (if
                  ((:streaming_options ep) country)
                  "|✓"
                  "|"))
              "\n")))))))

(let [episode-data (kcore/select episodes
                     (kcore/where {:ignore 0}))]
  (->> episode-data
       (map throttled-options)
       (remove (comp empty? :streaming_options))
       (sort-by :episode_id)
       format-results
       print))
