(require 'leiningen.exec)
(leiningen.exec/deps '[[org.clojure/clojure "1.7.0"]
                       [enlive "1.1.6"]
                       [clj-http "2.0.0"]
                       [throttler "1.0.0"]])

(import 'java.net.URL)
(require '[clojure.pprint :refer :all]
         '[clojure.java.io :as io]
         '[net.cgrand.enlive-html :as html]
         '[clj-http.client :as http]
         '[throttler.core :refer [throttle-fn]])

(def urls
  (map
    #(str "http://mostsacked.com/allMovies.cfm?"
          "order=sack_order&direc=ASC&startRow="
          %
          "&searchName=")
    (iterate (partial + 15) 0)))

(defn parse-id [url]
  (last
    (clojure.string/split url #"/")))

(defn parse-row [tr]
  (let [cells (html/select tr [:td])]
    {:rt_id (-> cells second :content first :attrs :href parse-id)
     :imdb_id (-> cells (nth 2) :content first :attrs :href parse-id)
     :episode_id (-> cells (nth 3) :content first Integer.)
     :title (-> cells (nth 4) :content first :content first)}))

(with-open [writer (io/writer "episode_data.clj")]
  (loop [pages urls]
    (let [resource (html/html-resource (URL. (first pages)))
          rows (html/select
                 (first (html/select resource [:table.table-striped]))
                 [:tbody :tr])]
      (pprint (first pages))
      (when-not
        (-> rows
            first
            html/text
            clojure.string/trim
            (= "No matching films found!"))
        (doseq [row rows]
          (pprint (parse-row row) writer))
        (recur (rest pages))))))

