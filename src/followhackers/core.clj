(ns followhackers.core
  ;; so many use cases ;)
  (:use [compojure.core]
        [hiccup.page]
        [hiccup.form]
        [hiccup.element]
        [cheshire.core]
        [postal.core]
        [ring.middleware.params]
        [ring.middleware.session]
        [ring.util.response])
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [org.httpkit.client :as http]))


;; TODO: date fix last 24 hours
;; TODO: polling to check every 24 hour and replace celebs db, right before email

;; TODO: assoc/dissoc responses
;; (def f (future (Thread/sleep 10000) (println "done") 100))

;; TODO: commit and tag release
;; TODO: search for multiple users at once
;; TODO: vote for us
;; TODO: feedback bar
;; TODO: fan subscribe counter
;; TODO: analytics

;; TODO: nothing should be there at start page

;; data

;;(def celebs (atom {}))
(defn save-celebs! [] (spit "data/celebs.db" (prn-str @celebs)))
(defn load-celebs! [] (reset! celebs (read-string (slurp "data/celebs.db"))))

;;(def fans (atom {}))
(defn save-fans! [] (spit "data/fans.db" (prn-str @fans)))
(defn load-fans! [] (reset! fans (read-string (slurp "data/fans.db"))))

;;(save-celebs!)
;;(save-fans!)
;;(load-celebs!)
;;(load-fans!)

@celebs

;; http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=pg&pretty_print=true&filter[fields][create_ts]=[2013-09-20T00:00:00Z%20+%20TO%20+%20*]
;; (def hnurl "http://api.thriftdb.com/api.hnsearch.com/items/_search?filter[fields][username]=oskarth")
;;"http://api.thriftdb.com/api.hnsearch.com/items/_search?sortby=create_ts=asc&limit=100&?filter[fields][username]=oskarth&filter[fields][create_ts]=[2013-01-01T00:00:00Z+TO+*]"
;; http://api.thriftdb.com/api.hnsearch.com/items/_search?sortby=create_ts%20asc&filter[fields][username]=oskarth&filter[fields][create_ts]=[2013-09-20T00:00:00Z+TO+*]


;; utils

(defn get-results [u]
  (get (parse-string (:body u))
       "results"))

;; TODO oops acomment
(defn htmlify [res]
  [:br]
  (for [n (range (count res))] (acomment res n)))

;; TODO this should only happen at polling
(defn populate-celebs! []
  (load-celebs!) ;; first, to update celebs atom

  (for [name (keys @celebs)]
    (fetch-celeb! name)))

;; TODO update date
(defn fetch-celeb! [name]
 (let [url1 "http://api.thriftdb.com/api.hnsearch.com/items/_search?sortby=create_ts%20desc&filter[fields][username]="
        url2 "&filter[fields][create_ts]=[2013-09-25T00:00:00Z+TO+*]"
       u1 (http/get (str url1 name url2))]

   ;; store html in db
   (swap! celebs assoc name
          (html5 (htmlify (get (parse-string (:body @u1)) "results")))))
 (save-celebs!))


;; TODO: __
;; not quite, but ish; iterate over vals and only if exists
;; (spit "/tmp/foo" (html5 @celebs))

(defn email! [to body]
  (send-message ^{:host "smtp.gmail.com" :user "followhackers" :pass (System/getenv "FHPWD") :ssl :true}
                {:from "followhackers@gmail.com" :to to "subject" "Follow Hackers: TODAY" :body body}))



;; html partials

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

(defn make-show [res]
  (html5
   [:br]
   (for [n (range (count res))] (acomment res n))))

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



;; api stuff

(defn httptest [q]
  (let [url1 "http://api.thriftdb.com/api.hnsearch.com/items/_search?sortby=create_ts%20desc&filter[fields][username]="
        url2 "&filter[fields][create_ts]=[2013-09-25T00:00:00Z+TO+*]"
        u1 (http/get (str url1 q url2))]
    (println "response's status: " (:status @u1))
    (make-show (get (parse-string (:body @u1)) "results"))))



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
   (if (empty? q) "" (httptest q))
   [:br]
   (if (= q "") "" (email-form q))))


(defn update-fans! [q e]
  ;; => {"mail1" #{"pg" "grellas"}, "mail2" #{"pg" "tokenadult"}
  (swap! fans
         (fn [m] (assoc m (str e)
                       (conj (get m (str e)) (str q)))))
  (println "Fans: " @fans)
  (save-fans!))

(defn update-celebs! [q e]
  ;; => {"pg" "ALL-THE-TEXTS-IN-A-STR?", "grellas", "TEXT"}
  ;; the text will be populate at polling
  (swap! celebs
         (fn [m] (assoc m (str q) "")))
  (println "Celebs: " @celebs)
  (save-celebs!))


;; TODO side effects
(defn assoc-page [q e]
  (update-fans! q e)
  (update-celebs! q e)

  ;; TODO: this is what it would've looked like yesterday
  ;; TODO: make async
  (email! e
          (str "You subscribed to " q "\n\n To unsubscribe, click HERE.")) ;; TODO: fix body and unsub

  (str "You've subscribed to " q ". Check your inbox!"))



;; TODO side effects
(defn dissoc-page [q e]
  (str "You've unsubscribed from " q "."))

(defn dissoc-all-page [q e]
  ;; TODO, POST form too
  )



;; routes and app

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
