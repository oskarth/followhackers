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

;;
;; WARNING: This code contains side-effects that can affect other human beings!
;; It SENDS EMAILS TO PEOPLE at set intervals
;; See (polling!!) (horrible name) for more
;;

;; TODOS for a rainy day
;;------------------------------------------------------------------------------
;; TODO: search for multiple users at once
;; TODO: vote for us
;; TODO: feedback bar
;; TODO: fan subscribe counter
;; TODO: analytics fix?
;; TODO: margins at end if long results
;; TODO: fix unsubscribe
;; TODO: autofocus search
;; TODO: sup with celeb (load-celeb!) mismatch?
;; TODO: chime-in is really cpu-intensive, temp or not? other soln?
;; TODO: fix depre features in clj-time, ms
;; TODO: figure out how to get the ENV to work in emacs for repl poll testing

;; atm swap manually when replling and debugging email
(def passwd (System/getenv "FHPWD"))
;;(def passwd "")

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

(defn date [] (str (time/month (time/today)) "." (time/day (time/today))))

(defn email! [to content]
  (send-message ^{:host "smtp.gmail.com" :user "followhackers" :pass passwd :ssl :true}
                {:from "followhackers@gmail.com" :to to "subject" (str "Follow Hackers: " (date))
                 :body [{:type "text/html" :content content}]}))


;; html partials

(defn search-form [q]
  (html5 (form-to [:POST "/search"]
                  "Search "
                  [:input {:type "text" :name "q" :value (or q "") :size "17"}])))

(defn dissoc-form []
  (html5 (form-to [:POST "/dissoc"]
                  "Dissoc email "
                  [:input {:type "text" :name "q" :value "" :size "17"}])))

(defn email-form [q]
  (html5
   [:h1 "Get daily updates from " (str q) "."]
   (form-to [:POST "/assoc"]
                 "Email "
                 [:input {:type "text" :name "e" :value "" :placeholder "" :size "17"}]
                 [:input {:type "hidden" :name "q" :value (str q)}])))


;; these are ugly but they work!

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

;;---

(defn get-mail-content [fan]
  (html5
   (for [celeb (get (load-fans!) fan)]
     (get (load-celebs!) celeb))))

(defn get-mail-body [fan]
  (html5
   [:h2 "Activity last 24 hours"]
   (get-mail-content fan)
   [:br] [:hr] "To unsubscribe, click " (link-to "http://hs.clojurecup.com/dissoc" "here")))



;; Side effects purgatory.
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

(defn remove-fan! [name]
  (swap! fans dissoc name)
  (save-fans!))



;; SIDE EFFECT HELL - fetching, polling etc
;; !! is the Ninth Circle. This is what it looks like
;; https://upload.wikimedia.org/wikipedia/commons/4/44/Gustave_Dore_Inferno34.jpg

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

;; fetches all celebs
(defn populate-celebs! []
  (for [name (keys (load-celebs!))]
    (fetch-celeb! name)))

;; html for all celebs that fan subscribes to

;; loops over all fans and emails them their digest
(defn mass-mail!! []
  (doseq [fan (keys (load-fans!))]
    (email! fan (get-mail-body fan))))


;; every 24th hour: (take 10 (periodic-seq (time/today-at-midnight) (time/hours 24)))
(defn polling!! []
  (chime-at (rest
             ;; TODO: change this temporary test value!
             ;; SUPER IMPORTANT.

             ;;(periodic-seq (time/now) (time/minutes 5))
             (periodic-seq (time/today-at-midnight) (time/hours 24)))
            (fn [time]
              ;; mails all fans with their latest gossip
              (do
                ;; fetch all celebs
                (populate-celebs!)
                (email! "me@oskarth.com" "FYI: Mass mailing commencing.")
                (mass-mail!!)))))

;; this is when it will send emails
;; (take 3 (rest (periodic-seq (time/now) (time/minutes 15))))
;; (take 3 (rest (periodic-seq (time/today-at-midnight) (time/hours 24))))



;; pages

(defn template [& body]
  (html5
   [:head
    [:title "Follow Hackers"]
    (include-css "css/style.css")
    analytics]
   [:body
    [:h4.logo "Follow Hackers"]
    [:p.byline (link-to "http://clojurecup.com/app.html?app=hs" "Vote for us in Clojure Cup 2013")]
    [:h1 "Search for hackers to follow."]
    [:p "1. Search for " (link-to "http://www.news.ycombinator.com" "HN") " users (only the last 24 hours of activity are displayed)." [:br]
     "2. Write your email." [:br]
     "3. Get daily emails with their activity."]
    [:br]
    body
    [:hr]
    [:div.footer "CSS from " (link-to "http://www.news.ycombinator.com" "HN")
     ". API from " (link-to "http://www.hnsearch.com" "HNSearch")
     ". Made by " (link-to "http://twitter.com/oskarth" "@oskarth") "."]]))

(defn index-page [q e]
  (template
   (search-form q)
   (if (empty? q) "" (render-results! q))
   [:br]
   (if (= q "") "" (email-form q))))


;; TODO: fix multi so it send for all, or do several at once?
(defn assoc-page [celeb fan]
  (update-fans! celeb fan)
  (email! fan (get-mail-body fan))
  (html5 "You've subscribed to " celeb ". Check your inbox!"
         [:br] [:br]
       (link-to "http://hs.clojurecup.com" "Want more?")))

;; page with form where you unsubscribe
(defn dissoc-page []
  (html5 (dissoc-form)))

;; this also includes the html render code
(defn unsubscribe! [q]
  (remove-fan! q)
  (str "You've been unsubscribed."))

;; routes and app

(defroutes app-routes
  (GET "/" [q e] (index-page "" ""))
  (POST "/search" [q e] (index-page q ""))
  (POST "/assoc" [q e] (assoc-page q e))
  (GET "/dissoc" [] (dissoc-page))
  (POST "/dissoc" [q] (unsubscribe! q))
  (route/resources "/")
  (route/not-found "404"))

(def app
  (handler/site app-routes))

;; starts polling upon startup
(polling!!)
