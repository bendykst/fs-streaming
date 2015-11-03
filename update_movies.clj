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

(def sqll (kdb/sqlite3 {:db "movies_test.db"}))
(kdb/defdb db sqll)
(kcore/defentity movies)

(def rt-api-key (env :rt-api-key))

(defn episode-exists? [id]
  ((complement empty?)
    (kcore/select movies
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
        title (:title ep)
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
        :media-title (sanitize-title (last parsed))))))

(defn get-episodes []
  (->> "http://feeds.frogpants.com/filmsack_feed.xml"
       feedparser/parse-feed
       :entries
       (map #(select-keys % [:title]))
       (keep parse-title)))

(defn trunc [s n]
  (subs s 0 (min (count s) n)))

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
  (println (str "  [" (count options) "] No Match")))

(defn get-cisi-from-imdb [imdb-id]
  (let [url (str "http://www.canistream.it/external/imdb/" imdb-id "?l=default")
        resource (html/html-resource (URL. url))
        cisi-id (-> resource
                    (html/select [:div.search-result])
                    first
                    :attrs
                    :rel)]
    cisi-id))

(defn find-ids [title]
  (let [resp (http/get
               "http://api.rottentomatoes.com/api/public/v1.0/movies.json"
               {:query-params {:apikey rt-api-key
                               :q title}
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
    (println "\n  Adding" title)
    (display-options options)
    (print "> ")
    (flush)
    (let [choice (Integer. (read-line))]
      (when (and ((complement neg?) choice) (< choice (count options)))
        (-> options
            (nth choice)
            (clojure.set/rename-keys {:id :rt_id})
            (#(assoc % :imdb_id (->> % :alternate_ids :imdb (str "tt"))))
            (#(assoc % :cisi_id (-> % :imdb_id get-cisi-from-imdb)))
            (select-keys [:title :imdb_id :rt_id :cisi_id]))))))

(defn update-database []
  (let [missing-eps (->> (get-episodes)
                         (remove #(nil? (:episode_id %)))
                         (remove
                           #(episode-exists? (Integer. (:episode_id %)))))
        movie-ids (doall (map (comp find-ids :media-title) missing-eps))]
    (dorun
      (map
        (fn [ep ids]
          (when ids
            (kcore/insert movies
              (kcore/values
                (select-keys
                  (merge ep ids)
                  [:title :imdb_id :rt_id :cisi_id :episode_id])))))
        missing-eps movie-ids))))

(update-database)

