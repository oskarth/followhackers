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
            [clj-time.core :as time]
            [clj-time.coerce :as timec]
            [clj-time.periodic :refer [periodic-seq]]
            [compojure.route :as route]
            [org.httpkit.client :as http]
            [chime :refer [chime-at]]))

;; TODO: if empty, don't show
;; TODO: polling
;; TODO: polling to check every 24 hour and replace celebs db, right before email
;; TODO: assoc/dissoc responses
;; (def f (future (Thread/sleep 10000) (println "done") 100))
;; TODO: commit and tag release
;; TODO: search for multiple users at once
;; TODO: vote for us
;; TODO: feedback bar
;; TODO: fan subscribe counter
;; TODO: analytics fix?
;; TODO: margins at end if long results
;; TODO: fix unsubscribe
;; TODO: autofocus search
;; TODO: sup with celeb (load-celeb!) mismatch?

(def analytics
"<script>
  (function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]||function(){
  (i[r].q=i[r].q||[]).push(arguments)},i[r].l=1*new Date();a=s.createElement(o),
  m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;m.parentNode.insertBefore(a,m)
  })(window,document,'script','//www.google-analytics.com/analytics.js','ga');

  ga('create', 'UA-44439591-1', 'clojurecup.com');
  ga('send', 'pageview');

</script>")


;; data

(def celebs (atom {}))
(defn save-celebs! [] (spit "data/celebs.db" (prn-str @celebs)))
(defn load-celebs! [] (reset! celebs (read-string (slurp "data/celebs.db"))))

(def fans (atom {}))
(defn save-fans! [] (spit "data/fans.db" (prn-str @fans)))
(defn load-fans! [] (reset! fans (read-string (slurp "data/fans.db"))))



;; load data from disk at startup
(load-celebs!)
(load-fans!)



;; utils

(defn midnight [] (timec/to-string (time/minus- (time/today-at-midnight) (time/days 1))))

(defn hn-link [id name] (link-to (str "https://news.ycombinator.com/item?id=" id) name))

(defn email! [to content]
  (send-message ^{:host "smtp.gmail.com" :user "followhackers" :pass (System/getenv "FHPWD") :ssl :true}
                {:from "followhackers@gmail.com" :to to "subject" "Follow Hackers: TODAY"
                 :body [{:type "text/html" :content content}]}))



;; html partials

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
                 [:input {:type "hidden" :name "q" :value (str q)}])))

(defn make-submission [resi]
  (html5 " | submitted: " (link-to (get-in resi ["item" "url"])
                                   (get-in resi ["item" "title"]))
   [:br] [:br]))

(defn make-acomment [resi]
  (html5 " | on: " (hn-link (get-in resi ["item" "discussion" "id"])
                            (get-in resi ["item" "discussion" "title"]))
         [:br] [:span (get-in resi ["item" "text"])]))

(defn acomment [res i]
  [:div.comment [:p.comment
         "by " (get-in (nth res i) ["item" "username"]) " | "
         (hn-link (get-in (nth res i) ["item" "id"]) "link")
         (if (nil? (get-in (res i) ["item" "discussion"]))
           (make-submission (nth res i))
           (make-acomment (nth res i)))]])



;; update data functions

;; store celebs html in db
;; schema: {"pg" "HTML_TEXT" "grellas" "HTML_TEXT2"}
(defn update-celeb! [celeb resp]
    (let [res (get (parse-string (:body resp)) "results")]
    (swap! celebs assoc celeb
           (html5 [:br] (for [n (range (count res))] (acomment res n))))
    (save-celebs!)))

;; store fans in db
;; schema: {"mail1" #{"pg" "grellas"}, "mail2" #{"pg" "tokenadult"}
(defn update-fans! [celeb fan]
  (swap! fans
         (fn [m] (assoc m (str fan)
                       (conj (set (get m (str fan))) (str celeb)))))
  (save-fans!))



;; fetching, polling etc

;; uses HNSearch API to get latest activity from the specified user
(defn fetch-celeb! [user]
 (let [url1 "http://api.thriftdb.com/api.hnsearch.com/items/_search?sortby=create_ts%20desc&filter[fields][username]="
       url2 "&filter[fields][create_ts]=["
       url3 "+TO+*]"
       resp (http/get (str url1 user url2 (midnight) url3))]
   (update-celeb! user @resp)))

(defn render-results! [name]
  (fetch-celeb! name)
  (html5 (get (load-celebs!) name)))


;; TODO this should only happen at polling
(defn populate-celebs! []
  (load-celebs!) ;; first, to update celebs atom

  (for [name (keys @celebs)]
    (fetch-celeb! name)))


;; every 24th hour: (take 10 (periodic-seq (time/today-at-midnight) (time/hours 24)))
;; TODO how/when start?
;; be careful.
(defn polling!! []
  (chime-at (rest
             ;; TODO: change this temporary test value!
             ;;(periodic-seq (time/today-at-midnight) (time/hours 24))
             (periodic-seq (time/today-at-midnight) (time/minutes 5))
             )
            (fn [time]
              ;; loop through all and then email each.
              ;; start with one email here
              (email! "me@oskarth.com"
                      "content"))))



;; pages

(defn template [& body]
  (html5
   [:head
    [:title "Follow Hackers"]
    (include-css "css/style.css")
    analytics]
   [:body
    [:h4.logo "Follow Hackers"]
    [:p.byline "Made during Clojure Cup 2013"] ;; TODO vote for us
    [:h1 "Search for hackers to follow."]
    [:br]
    body]))

(defn index-page [q e]
  (template
   (search-form q)
   (if (empty? q) "" (render-results! q))
   [:br]
   (if (= q "") "" (email-form q))))


  ;; TODO: fix multi so it send for all, or do several at once?
(defn assoc-page [q e]
  (update-fans! q e)
  (email! e
          (html5
           "Activity last 24 hours:" [:br] [:br]
           (get @celebs q)
           [:br] [:br] "To unsubscribe, click " (link-to "hs.clojurecup.com/dissoc" "here")))

  (str "You've subscribed to " q ". Check your inbox!"))

;; TODO unsubscribe all
(defn dissoc-page [q e]
  (str "You've unsubscribed from " q "."))



;; routes and app

(defroutes app-routes
  (GET "/" [q e] (index-page "" ""))
  (POST "/search" [q e] (index-page q ""))
  (POST "/assoc" [q e] (assoc-page q e))
  (GET "/dissoc" [q e] (dissoc-page q e))
  (POST "/dissoc" [q e] (dissoc-page q e))
  (route/resources "/")
  (route/not-found "404"))

(def app
  (handler/site app-routes))
