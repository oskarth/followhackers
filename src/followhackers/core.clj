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

;; TODO: search for multiple users at once
;; TODO: sort by last day
;; TODO: assoc/dissoc responses
;; TODO: postal for email
;; (def f (future (Thread/sleep 10000) (println "done") 100))
;; TODO: commit and tag release


;; "http://api.thriftdb.com/api.hnsearch.com/items/_search?filter [fields] [username]=pg&pretty_print=true&filter [fields] [create_ts]= [2013-09-20T00:00:00Z%20+%20TO%20+%20*]"

;; http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=pg&pretty_print=true&filter[fields][create_ts]=[2013-09-20T00:00:00Z%20+%20TO%20+%20*]

;; simple case: change pg to foo
(def hnurl "http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=oskarth")

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

;; TODO: what about dates? item create_ts
(defn acomment [res i]
  (html5
   [:div
    "by " (get-in (nth res i) ["item" "username"]) " | "
    (hn-link (get-in (nth res i) ["item" "id"]) "link")

    ;; if discussion is nil, it's a submission
    (if (nil? (get-in (res i) ["item" "discussion"]))
      (make-submission (nth res i))
      (make-acomment (nth res i)))]))

(defn get-results [u]
  (get (parse-string (:body u))
         "results"))

;; TODO: bug with indexoutofbounds
(defn make-show [res]
  (html5 (acomment res 0)
            (acomment res 1)
            (acomment res 2)))

(defn httptest [q]
  (let [u1 (http/get (str "http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=" q))
        u2 (http/get (str "http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=" "pg"))]
    ;; Handle responses one-by-one, blocking as necessary
    ;; Other keys :headers :body :error :opts
    (println "response1's status: " (:status @u1))
    (println "response2's status: " (:status @u2))
    (make-show (get-results @u1))))

(defn search-form []
  (html5 (form-to [:POST "/search"]
                 "Search "
                 [:input {:type "text" :name "q" :value "" :size "17"}])))

(defn email-form [q]
  (html5
   [:h2 "Get daily updates from " (str q) " in your mail."]
   (form-to [:POST "/assoc"]
                 "Assoc email "
                 [:input {:type "text" :name "e" :value "" :size "17"}])
         (form-to [:POST "/dissoc"]
                 "Dissoc email "
                 [:input {:type "text" :name "e" :value "" :size "17"}])))

(defn template [& body]
  (html5
   [:head
    [:title "Follow Hackers"]
    (include-css "css/style.css")]
   [:body
    [:h4.logo "Follow Hackers"]
    [:p.byline "Made during Clojure Cup 2013"]
    [:h1 "Search for hackers to follow."]
    (search-form)
    [:br]
    body]))

(defn index-page [q e]
  (template
   (httptest q)
   [:br]
   (if (= q "") "" (email-form q))))

(defroutes app-routes
  (GET "/" [q e] (index-page "" ""))
  (POST "/search" [q e] (index-page q ""))
  (GET "/bar" [] (bar))
  (route/resources "/")
  (route/not-found "404"))

(def app
  (handler/site app-routes))
