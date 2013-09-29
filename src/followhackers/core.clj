(ns followhackers.core
  (:use [compojure.core]
        [hiccup.page]
        [hiccup.form]
        [hiccup.element]
        [cheshire.core]
        [ring.middleware.params]
        [ring.middleware.session]
        [ring.util.response])
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [org.httpkit.client :as http]))

;; TODO: sort by last day

;; TODO: assoc/dissoc responses
;; TODO: postal for email
;; (def f (future (Thread/sleep 10000) (println "done") 100))
;; TODO: commit and tag release
;; TODO: search for multiple users at once
;; TODO: feedback thingy
;; TODO: vote for us flerp?

;; data

(def celebs (atom {"pg" "all pg text"}))
(defn save-celebs! [] (spit "data/celebs.db" (prn-str @celebs)))
(defn load-celebs! [] (reset! celebs (read-string (slurp "data/celebs.db"))))

(def fans (atom {"me@oskarth.com" ["pg grellas"]}))
(defn save-fans! [] (spit "data/fans.db" (prn-str @fans)))
(defn load-fans! [] (reset! fans (read-string (slurp "data/fans.db"))))

(save-celebs!)
(save-fans!)
(load-celebs!)
(load-fans!)



;; http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=pg&pretty_print=true&filter[fields][create_ts]=[2013-09-20T00:00:00Z%20+%20TO%20+%20*]
;; (def hnurl "http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=oskarth")
;;"http://api.thriftdb.com/api.hnsearch.com/items/_search?sortby=create_ts=asc&limit=100&?filter[fields][username]=oskarth&filter[fields][create_ts]=[2013-01-01T00:00:00Z+TO+*]"
;; http://api.thriftdb.com/api.hnsearch.com/items/_search?sortby=create_ts%20asc&filter[fields][username]=oskarth&filter[fields][create_ts]=[2013-09-20T00:00:00Z+TO+*]



(defn hn-link [id name]
  (link-to (str "https://news.ycombinator.com/item?id=" id) name))

(defn make-acomment [resi]
  (html5
   " | on: " (hn-link (get-in resi ["item" "discussion" "id"])
                      (get-in resi ["item" "discussion" "title"]))
   [:br]
   [:span {:class "comment"} (get-in resi ["item" "text"])]))

(defn make-submission [resi]
  ;; TODO: link to submission?
  (html5
   " | submitted: " (link-to (get-in resi ["item" "url"])
                             (get-in resi ["item" "title"]))
   [:br] [:br]))

(defn acomment [res i]
  [:div
    "by " (get-in (nth res i) ["item" "username"]) " | "
    (hn-link (get-in (nth res i) ["item" "id"]) "link")

    ;; if discussion is nil, it's a submission
    (if (nil? (get-in (res i) ["item" "discussion"]))
      (make-submission (nth res i))
      (make-acomment (nth res i)))])

(defn get-results [u]
  (get (parse-string (:body u))
       "results"))

;; hits is total on server
;; we can just do count right now
;; TODO: bug with indexoutofbounds
(defn make-show [res]
  (html5
   [:br]
   (for [n (range (count res))] (acomment res n))))

;;(for [n (range 10)] (acomment res n))

;; todo set date
(defn httptest [q]
  (let [url1 "http://api.thriftdb.com/api.hnsearch.com/items/_search?sortby=create_ts%20desc&filter[fields][username]="
        url2 "&filter[fields][create_ts]=[2013-09-25T00:00:00Z+TO+*]"
        u1 (http/get (str url1 q url2))
        ;;u1 (http/get (str "http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=" q))
        u2 (http/get (str "http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=" "pg"))]
    ;; Handle responses one-by-one, blocking as necessary
    ;; Other keys :headers :body :error :opts
    (println "response1's status: " (:status @u1))
    (println "response2's status: " (:status @u2))
    (make-show (get (parse-string (:body @u1)) "results"))))

(defn search-form [q]
  (html5 (form-to [:POST "/search"]
                  "Search "
                  [:input {:type "text" :name "q" :value (or q "") :size "17"}])))


(defn email-form [q]
  (html5
   [:h1 "Get daily updates from " (str q) "."]
   (form-to [:POST "/assoc"]
                 "Email "
                 [:input {:type "text" :name "e" :value "" :placeholder "" :size "17"}]
                 [:input {:type "hidden" :name "q" :value (str q)}])
         #_(form-to [:POST "/dissoc"]
                  "Dissoc "
                  [:input {:type "text" :name "e" :value "" :placeholder "test@example.com" :size "17"}])))

(defn template [& body]
  (html5
   [:head
    [:title "Follow Hackers"]
    (include-css "css/style.css")]
   [:body
    [:h4.logo "Follow Hackers"]
    [:p.byline "Made during Clojure Cup 2013"] ;; TODO vote for us
    [:h1 "Search for hackers to follow."]
    [:br]
    body]))

(defn index-page [q e]
  (template
   (search-form q)
   (httptest q)
   [:br]
   (if (= q "") "" (email-form q))))

;; TODO side effects
(defn assoc-page [q e]
  (println "subscribe e " e " to q " q) ;; db time
  (str "You've subscribed to " q ". Check your inbox!"))

;; TODO side effects
(defn dissoc-page [q e]
  (str "You've unsubscribed from " q "."))

(defn dissoc-all-page [q e]
  ;; TODO, POST form too
  )

(defroutes app-routes
  (GET "/" [q e] (index-page "" ""))
  (POST "/search" [q e] (index-page q ""))
  (POST "/assoc" [q e] (assoc-page q e))
  (POST "/dissoc" [q e] (dissoc-page q e))
  (GET "/dissoc-all" [q e] (dissoc-all-page q e))
  (POST "/dissoc-all" [q e] (dissoc-all-page q e))
  (route/resources "/")
  (route/not-found "404"))

(def app
  (handler/site app-routes))
