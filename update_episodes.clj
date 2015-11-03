(require 'leiningen.exec)
(leiningen.exec/deps '[[org.clojure/clojure "1.7.0"]
                       [enlive "1.1.6"]
                       [environ "1.0.1"]
                       [korma "0.4.2"]
                       [org.xerial/sqlite-jdbc "3.8.11.2"]
                       [adamwynne/feedparser-clj "0.5.2"]
                       [clj-http "2.0.0"]
                       [throttler "1.0.0"]])
(import 'java.net.URL)
(require '[feedparser-clj.core :as feedparser]
         '[net.cgrand.enlive-html :as html]
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
(kcore/defentity ignored)

(def rt-api-key (env :rt-api-key))

(defn episode-exists? [id]
  ((complement empty?)
    (kcore/select episodes
      (kcore/where
        {:episode_id id}))))

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
        title (:episode_title ep)
        parsed (or
                 (re-matches normal-title title)
                 (re-matches bonus-sack-title title)
                 (re-matches commentary-title title))]
    (when parsed
      (assoc
        ep
        :episode_id (when
                      (not= (second parsed) "")
                      (Integer. (second parsed)))
        :media_title (sanitize-title (last parsed))))))

(defn get-episodes []
  (->> "http://feeds.frogpants.com/filmsack_feed.xml"
       feedparser/parse-feed
       :entries
       (map #(clojure.set/rename-keys % {:title :episode_title}))
       (map #(select-keys % [:episode_title]))
       (keep parse-title)))

(defn display-options [options]
  (print
    (apply str
      (flatten
        (map-indexed
          (fn [idx option]
            (list "  [" idx "] " (:title option)
                  " (" (:year option) ") - "
                  (first (:abridged_cast option)) ", "
                  (second (:abridged_cast option)) "\n"))
          options))))
  (println (str "  [" (count options) "] Skip"))
  (println (str "  [" (inc (count options)) "] Manual Search"))
  (println (str "  [" (+ 2 (count options)) "] Ignore Forever")))

(defn get-cisi-from-imdb [imdb-id]
  (let [url (str "http://www.canistream.it/external/imdb/" imdb-id "?l=default")
        resource (html/html-resource (URL. url))
        cisi-id (-> resource
                    (html/select [:div.search-result])
                    first
                    :attrs
                    :rel)]
    cisi-id))

(defn find-ids [{:keys [media_title episode_id episode_title] :as ep}]
  (let [resp (http/get
               "http://api.rottentomatoes.com/api/public/v1.0/movies.json"
               {:query-params {:apikey rt-api-key
                               :q media_title}
                :accept :json
                :as :json})
        options (->> resp
                     :body
                     :movies
                     (map #(select-keys % [:title
                                           :year
                                           :id
                                           :abridged_cast
                                           :alternate_ids]))
                     (map #(update-in % [:abridged_cast] (partial map :name))))]
    (println "\n  Adding" media_title)
    (display-options options)
    (print "> ")
    (flush)
    (let [choice (Integer. (read-line))]
      (cond
        (= choice (count options)) nil
        (= choice (inc (count options)))
          (do
            (print "Enter a search term:")
            (flush)
            (recur (assoc ep :media_title (read-line))))
        (= choice (+ 2 (count options)))
          (do
            (kcore/insert episodes
              (kcore/values {:media_title media_title
                             :episode_title episode_title
                             :episode_id episode_id
                             :ignore 1}))
            (println "\nDatabase updated"))
        (and ((complement neg?) choice) (< choice (count options)))
          (-> options
              (nth choice)
              (clojure.set/rename-keys {:title :media_title :id :rt_id})
              (#(assoc % :imdb_id (->> % :alternate_ids :imdb (str "tt"))))
              (#(assoc % :cisi_id (-> % :imdb_id get-cisi-from-imdb)))
              (assoc :ignore 0)
              (select-keys [:title :media_title :imdb_id :rt_id :cisi_id]))
        :else nil))))

(defn update-database []
  (let [missing-eps (->> (get-episodes)
                         (remove #(nil? (:episode_id %)))
                         (remove
                           #(episode-exists? (Integer. (:episode_id %)))))
        movie-ids (doall (map find-ids missing-eps))]
    (dorun
      (map
        (fn [ep ids]
          (when ids
            (kcore/insert episodes
              (kcore/values
                (select-keys
                  (merge ep ids)
                  [:media_title
                   :imdb_id
                   :rt_id
                   :cisi_id
                   :episode_title
                   :episode_id])))))
        missing-eps movie-ids))))

(update-database)
