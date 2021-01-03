;; Copyright © 2020 Atomist, Inc.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns atomist.main
  (:require [atomist.api :as api]
            [atomist.cljs-log :as log]
            [atomist.github]
            [atomist.json :as json]
            [cljs-node-io.core :as io]
            [cljs.core.async :refer [<!]]
            [cljs.pprint :refer [pprint]]
            [cljs.tools.reader.edn :as edn]
            [clojure.data]
            [goog.string :as gstring]
            [goog.string.format])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn transact [handler]
  (fn [request]
    (go
      (let [{:as data}
            (-> request
                :webhook
                :body
                (json/->obj))]
        (log/info "flux event data " (-> data :involvedObject :kind) (-> data :message) (-> data :reason) " -- " data)
        (<! (handler (assoc request :atomist/summary "flux")))))))

(defn ^:export handler
  [data sendreponse]
  (api/make-request
   data
   sendreponse
   (-> (api/finished :message "----> event handler finished")
       (transact)
       (api/log-event)
       (api/status :send-status (fn [{:atomist/keys [summary]}] summary)))))

