;; Copyright © 2021 Atomist, Inc.
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
            [clojure.string :as s]
            [goog.string :as gstring]
            [goog.string.format])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn progress-message
  "namespace/name configured|created"
  [s]
  (->> s
       (s/split-lines)
       (map #(re-find #"([\w_-]+)/([\w_-]+) (.*)" %))))

(defn commit
  "lookup a commit ref"
  [org repo sha]
  [{:schema/entity-type :git/org
    :schema/entity "$org"
    :git.org/name org}
   {:schema/entity-type :git/repo
    :schema/entity "$repo"
    :git.repo/name repo
    :git.repo/org "$org"}
   {:schema/entity-type :git/commit
    :schema/entity "$commit"
    :git.commit/sha sha
    :git.provider/url "https://github.com"
    :git.commit/repo "$repo"}])

(defn reconciliation
  "parse flux data and produce a reconciliation entity"
  [{:keys [message severity reason reportingInstance]
    {:keys [commit_status revision]} :metadata
    {:keys [namespace name uid apiVersion resourceVersion]} :involvedObject}]
  {:schema/entity-type :flux/reconciliation
   :flux.reconciliation/uid uid
   :flux.reconciliation/name name
   :flux.reconciliation/status (keyword "flux.reconciliation.status" reason)
   :flux.reconciliation/message message
   :flux.reconciliation/sha "$commit"})

(defn revision->sha
  "extract a sha from a flux revision
     return sha or nil"
  [revision]
  (if revision
    (second (re-find #".*/(.*)" revision))))

(defn transact [handler]
  (fn [request]
    (go
      (let [{:as data}
            (-> request
                :webhook
                :body
                (json/->obj))]
        (cond
          ;; Kustomization Progressing
          (and (= "Kustomization" (-> data :involvedObject :kind))
               (= "Progressing" (-> data :reason)))
          (let [{:keys [message reportingInstance]
                 {:keys [commit_status revision]} :metadata
                 {:keys [name uid]} :involvedObject} data]
            ;; message contains Object updates
            (log/infof "FLUX: Progressing %s on %s (%s,%s,%s)" (progress-message message) revision name reportingInstance uid)
            (<! (api/transact request (concat
                                       (commit "slimslender" "flux-test" (revision->sha revision))
                                       [(reconciliation data)]))))

          ;; Kustomization ReconciliationSucceeded
          (and (= "Kustomization" (-> data :involvedObject :kind))
               (= "ReconciliationSucceeded" (-> data :reason)))
          (let [{:keys [reportingInstance]
                 {:keys [commit_status revision]} :metadata
                 {:keys [name uid]} :involvedObject} data]
            (log/infof "FLUX:  Success %s (%s,%s,%s)" revision name reportingInstance uid)
            (<! (api/transact request (concat
                                       (commit "slimslender" "flux-test" (revision->sha revision))
                                       [(reconciliation data)]))))

          ;; GitRepository fetching a revision
          (and (= "GitRepository" (-> data :involvedObject :kind))
               (= "info" (-> data :reason)))
          (let [{:keys [message severity reason reportingInstance]
                 {:keys [namespace name uid apiVersion resourceVersion]} :involvedObject} data]
            ;; Fetched revision: main/0dd97825ece28867bd12e190b0fdcd9d5cf32dda
            (log/infof "FLUX:  GitRepository %s (%s,%s,%s)" message name reportingInstance uid))

          ;; Artifact failed
          (and (= "Kustomization" (-> data :involvedObject :kind))
               (= "error" (-> data :severity)))
          (let [{:keys [message severity reason reportingInstance]
                 {:keys [revision]} :metadata
                 {:keys [namespace name uid apiVersion resourceVersion]} :involvedObject} data]
            (log/warnf "FLUX:  error %s %s (%s,%s,%s)" message revision name reportingInstance uid))

          :else
          (log/infof "FLUX: unknown event data %s:%s:%s -- %s %s"
                     (-> data :involvedObject :kind)
                     (-> data :message)
                     (-> data :reason)
                     (-> data :reportingInstance)
                     data))
        (<! (handler (assoc request
                            :atomist/status
                            {:code 0
                             :reason "flux transacts"
                             :visibility :hidden})))))))

(defn ^:export handler
  [data sendreponse]
  (api/make-request
   data
   sendreponse
   (-> (api/finished)
       (transact)
       (api/log-event)
       (api/status))))

