(ns followhackers.core
  (:use [compojure.core]
        [hiccup.page]
        [hiccup.form]
        [hiccup.element]
        [cheshire.core])
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [org.httpkit.client :as http]))

;; "http://api.thriftdb.com/api.hnsearch.com/items/_search?filter [fields] [username]=pg&pretty_print=true&filter [fields] [create_ts]= [2013-09-20T00:00:00Z%20+%20TO%20+%20*]"

;; simple case: change pg to foo
(def hnurl "http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=oskarth")

;; BUG: comments vs submissions! atm it fuxxors cause (a) submission no text, (b) discussion title not same, prolly submission title?
;; maybe do only comments?


;; lets redefine res...
(def res
  (get (parse-string (:body @(http/get hnurl)))
       "results"))


(defn fetchtest [q]
  (let [url (http/get hnurl)]))

(defn hn-link [id name]
  (link-to (str "https://news.ycombinator.com/item?id=" id) name))

(defn make-acomment [resi]
  (html5
   " | on: " (hn-link (get-in resi ["item" "discussion" "id"])
                      (get-in resi ["item" "discussion" "title"]))
   [:br]
   [:span {:class "comment"} (get-in resi ["item" "text"])]))

(defn make-submission [resi]
  ;; todo, doesn't it make more sense to link to the hn submission?
  (html5
   " | submitted: " (link-to (get-in resi ["item" "url"])
                             (get-in resi ["item" "title"]))
   [:br] [:br]))
;; what about dates? item create_ts
(defn acomment [res i]
  (html5
   [:div
    "by " (get-in (nth res i) ["item" "username"]) " | "
    (hn-link (get-in (nth res i) ["item" "id"]) "link")

    ;; if discussion is nil, it's a submission
    (if (nil? (get-in (res i) ["item" "discussion"]))
      (make-submission (nth res i))
      (make-acomment (nth res i)))]))



;; automate with search, res sup
(defn bar []
  (template (acomment res 0)
            (acomment res 1)
            (acomment res 2)))


(defn search-form []
  (html5 (form-to [:POST "/search"]
                 "Search "
                 [:input {:type "text" :name "q" :value "" :size "17"}])))

  (defn template [& body]
    (html5
     [:head
      [:title "Follow Hackers"]
      (include-css "css/style.css")]
     [:body
      [:h4.logo "Follow Hackers"]
      [:h1 "Search for hackers to follow."]
      (search-form)
      [:br]
      body]))


;;[:h1 "Follow hackers on HN."]
(defn index-page []
  (template))

(defn httptest [q]
  (let [hn (http/get hnurl)
        response1 (http/get "http://http-kit.org/")
        response2 (http/get "http://clojure.org/")]
    ;; Handle responses one-by-one, blocking as necessary
    ;; Other keys :headers :body :error :opts
    (println "response1's status: " (:status @response1))
    (println "response2's status: " (:status @response2))
    @hn))

(defn search-page [q]
  (template
   [:h1 "Is this correct?"]
   (httptest q)))

(defroutes app-routes
  (GET "/" [] (index-page))
  (POST "/search" [q] (search-page q))
  (GET "/bar" [] (bar))
  (route/resources "/")
  (route/not-found "404"))

(def app
  (handler/site app-routes))

;; http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=pg&pretty_print=true&filter[fields][create_ts]=[2013-09-20T00:00:00Z%20+%20TO%20+%20*]
