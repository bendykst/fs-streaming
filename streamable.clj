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

(def sqll (kdb/sqlite3 {:db "movies.db"}))
(kdb/defdb db sqll)
(kcore/defentity movies)

(def movie-ids
  (reduce
    (fn [ids record]
      (assoc
        ids
        (:episode_id record)
        {:imdb (:imdb_id record)
         :cisi (:cisi_id record)
         :rt (:rt_id record)}))
    {}
    (kcore/select movies)))

(defn sanitize-title [title]
  (-> title
      (clojure.string/replace #"(?i:at nerdtacular \d+)" "")
      (clojure.string/replace #"(?i:live (watch|sack|viewing)( of)?)" "")
      (clojure.string/replace #"(?:\(?LIVE\)?)" "")
      (clojure.string/replace #"(?:NEWER)" "")
      (clojure.string/replace #"(?i:commentary track( edition)?)" "")
      (clojure.string/replace #"(?i:(: )?bonus sack:?)" "")
      (clojure.string/replace #"(?i:the one about)" "")
      (clojure.string/replace #"\s+" " ")
      (clojure.string/trim)))

(defn parse-title [ep]
  (let [normal-title
          #"Film Sack \#?(\d+):\s*[\"|“]?([^\"\!”]+)[\!]?[\"|”]?\s*"
        bonus-sack-title
          #"BONUS SACK:  Commentary Track for ()[\"|“]?([^\"\!”]+)[\!]?[\"|”]?\s*"
        commentary-title
          #"Film Sack COMMENTARY TRACK: ()[\"|“]?([^\"\!”]+)[\!]?[\"|”]?\s*"
        title (:title ep)
        parsed (or
                 (re-matches normal-title title)
                 (re-matches bonus-sack-title title)
                 (re-matches commentary-title title))]
    (when parsed
      (assoc
        ep
        :episode-id (when
                      (not= (second parsed) "")
                      (Integer. (second parsed)))
        :media-title (sanitize-title (last parsed))))))

(defn get-episodes []
  (->> "http://feeds.frogpants.com/filmsack_feed.xml"
       feedparser/parse-feed
       :entries
       (map #(select-keys % [:title :pubDate]))
       (keep parse-title)))

(defn extract-imdb-id [movie-data]
  (when-let
    [url (-> movie-data :links :imdb)]
    (last
      (clojure.string/split url #"/"))))

(defn get-cisi-id [ep imdb-id]
  (println "Attempting to add CISI id for" (:title ep))
  (let [resp (http/get
               "http://www.canistream.it/services/search"
               {:query-params {:movieName (:media-title ep)}
                :accept :json
                :as :json})]
    (if-let [matching-result (first
                               (filter
                                 #(= imdb-id (extract-imdb-id %))
                                 (:body resp)))]
      (do
        (kcore/update movies
          (kcore/set-fields {:cisi_id (:_id matching-result)})
          (kcore/where {:episode_id (:episode-id ep)}))
        (assoc
          ep
          :cisi-id
          (:_id matching-result)))
      (println "*** No CISI ID found for" (:title ep) "***"))))

(def throttled-id (throttle-fn get-cisi-id 1 :second))

(defn assoc-cisi-id [ep]
  (let [ids (-> ep :episode-id movie-ids)]
    (cond
      (:cisi ids) (assoc ep :cisi-id (:cisi ids))
      (:imdb ids) (throttled-id ep (:imdb ids))
      ids (println "*** No IMDB ID found for" (:title ep) "***")
      (:episode-id ep) (do
                         (kcore/insert movies
                           (kcore/values {:episode_id (:episode-id ep)
                                          :title (:media-title ep)}))
                         (println "Adding empty entry for" (:title ep)))
      :else (println "Skipping" (:title ep)))))

(defn assoc-streaming-options [ep]
  (println "Getting options for" (:media-title ep))
  (let [resp (http/get
               "http://www.canistream.it/services/query"
               {:query-params {:movieId (:cisi-id ep)
                               :attributes true
                               :mediaType "streaming"}
                :accept :json
                :as :json})]
    (assoc
      ep
      :streaming-options
      (->> resp :body vals (map :friendlyName) set))))

(def throttled-options (throttle-fn assoc-streaming-options 1 :second))

(defn format-results [eps]
  (let [streaming-sites (->> eps
                             (mapcat :streaming-options)
                             set
                             (sort-by #(if (= % "Netflix Instant") "AAA" %)))]
    (apply str
      (flatten
        (list
          "Episode"
          (for [service streaming-sites] (str " | " service))
          "\n:---"
          (for [_ streaming-sites] "|:---:")
          "\n"
          (for [ep eps]
            (list
              (:title ep)
              (for [site streaming-sites]
                (if
                  ((:streaming-options ep) site)
                  "|✓"
                  "|"))
              "\n")))))))

(let [results (->> (get-episodes)
                   (map assoc-cisi-id)
                   (filter identity)
                   (map throttled-options)
                   (remove (comp empty? :streaming-options)))]
  (println "----------------")
  (print (format-results (reverse results))))
