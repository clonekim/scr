(ns scr.core
  (:use ring.util.response)
  (:import (org.jsoup Jsoup)
           (java.net URLEncoder))
  (:require [compojure.core :refer :all]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.json :refer [wrap-json-response
                                          wrap-json-body
                                          wrap-json-params]]
            [org.httpkit.server :refer [run-server]]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [hiccup.page :as page]
            [scr.sql :as sql]
            [cheshire.core :refer [parse-string]])
  (:gen-class))

(defonce tout (* 1000 20))
(defonce hanja-tooltip-url "http://hanja.naver.com/tooltip.nhn?q=")

(defn get-doc
  ([url]
   (.get 
    (.timeout 
     (Jsoup/connect url) tout))))

(defn urlparam-encode [q]
  (URLEncoder/encode q "utf-8"))

(defn fetch
  ([url]
   (log/debug "fetching -- " url)
   (into [] (for [tree (.select (get-doc url) ".bordtop_bl a")] (.text tree))))
  ([url q]
   (log/debug "fetching -- " url q)
   (parse-string (.text (get-doc (str url (urlparam-encode q)))))))


(defn parse-item [el]
  (assoc {}
         :letter (get el "letter")
         :pronun (get el "letterPronun")
         :read_pronun (get el "readPronun")
         :theory (get el "theoryDescription")
         :bushou_letter (get-in el ["bushou" "bushouLetter"])
         :bushou_name (get-in el ["bushou" "bushouName"])
         :bushou_mean (get-in el ["bushou" "bushouMean"])
         :bushou_pronun (get-in el ["bushou" "bushouPronun"])
         :bushou_stroke (get-in el ["bushou" "bushouStroke"])
         :total_stroke (get el "totalStrokeCount")             
         :means  (get el "letterMeans")))


(defn go-chan [v kv]
  (doseq [i v]
    (async/go 
      (let [q (str (.charAt i 0))
            batch-id (sql/insert-batch {:prime_id (:prime-id kv) :letter q :url (str hanja-tooltip-url (urlparam-encode q)) :stat "START" :created (java.util.Date.)})]        
        (try
          (let [parsed (parse-item (fetch hanja-tooltip-url q))]
            (sql/insert-hanja (assoc parsed :batch_id batch-id :consonant (:consonant kv) ))
            (sql/update-batch {:batch-id batch-id :stat "END"}))
          (catch Exception e
            (sql/update-batch {:batch-id batch-id :stat "ERROR" :exception (.getMessage e)})))))))
      


(defn add-batch [url consonant]
  (let [pk (sql/insert-batch {:url url :stat "REGISTERED" :created (java.util.Date.)})]
    (log/debug "batch id -- " pk)
    (let [p (go-chan (fetch url) {:consonant consonant :prime-id pk})]
      (log/debug "chan -- " p))
    pk))

(defroutes app-routes
  (GET "/" [] 
       (page/html5 {:ng-app "app"}
                   [:head
                    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge"}]
                    [:title "== SCROLL =="]
                    (page/include-css "css/bootstrap.min.css")]
                   [:body
                    [:ng-view]
                    (page/include-js "js/app.js")]))

  (GET "/api/archive" []
       (response (sql/total-hanja)))

  (GET "/api/archive/:index" [index]
       (log/debug "query archive --" index)
       (response {:pronuns (sql/select-hanja-pronun index)
                   :rows (sql/select-hanja index)}))
  
  (GET "/api/archive/:index1/:index2" [index1 index2]
       (log/debug "query archive --" index1 ", " index2)
       (response {:pronuns (sql/select-hanja-pronun index1)
                  :rows (sql/select-hanja [index1 index2])}))

  (GET "/api/more-means/:id" [id]
       (sql/select-hanja-annotation id))

  (GET "/api/batch-stat" []
       (sql/select-batch))
  
  (GET "/api/batch-stat/:id" [id]
       (sql/select-batch id))

  (POST "/api/batch" [url consonant]
        (response {:batch_id (add-batch url consonant)}))

  (POST "/api/reset" []
        (log/debug "reset schema!!")
        (response {:stat (do 
                           (sql/drop-schema)
                           (sql/create-schema))})))


(def app
  (-> app-routes
      wrap-json-body
      wrap-json-params
      wrap-json-response
      (wrap-resource "public")
      wrap-content-type))

(defn -main []
  (log/info "listen on port 5678")
  (sql/init)
  (run-server #'app {:port 5678}))
