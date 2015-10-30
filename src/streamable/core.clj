(ns streamable.core
  (:require [feedparser-clj.core :as feedparser]
            [clojure.pprint :refer :all]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [throttler.core :refer [throttle-fn]])
  (:gen-class))

(defn sanitize-title [title]
  (-> title
      (clojure.string/replace #"(?i:at nerdtacular \d+)" "")
      (clojure.string/replace #"(?i:\(LIVE\))" "")
      (clojure.string/replace #"(?i:live (watch|sack|viewing)( of)?)" "")
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
        :episode (when
                   (not= (second parsed) "")
                   (Integer. (second parsed)))
        :media-title (sanitize-title (last parsed))))))

(defn get-episodes []
  (->> "http://feeds.frogpants.com/filmsack_feed.xml"
       feedparser/parse-feed
       :entries
       (map #(select-keys % [:title :pubDate]))
       (keep parse-title)))

(defn assoc-media-id [ep]
  (let [resp (http/get
               "http://www.canistream.it/services/search"
               {:query-params {:movieName (:media-title ep)}
                :accept :json
                :as :json})]
    (assoc
      ep
      :media-id
      (->> resp :body first :_id))))

(defn assoc-streaming-options [ep]
  (let [resp (http/get
               "http://www.canistream.it/services/query"
               {:query-params {:movieId (:media-id ep)
                               :attributes true
                               :mediaType "streaming"}
                :accept :json
                :as :json})]
    (assoc
      ep
      :streaming-options
      (->> resp :body vals (map :friendlyName) set))))

(def throttled-id (throttle-fn assoc-media-id 1 :second))
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

(defn -main
  [& args]
  (let [results (->> (get-episodes)
                     (map throttled-id)
                     (filter identity)
                     (map throttled-options)
                     (remove (comp empty? :streaming-options)))]
    (pprint results)
    (println "----------------")
    (print (format-results results))))
