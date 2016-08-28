(ns commiteth.routes.webhooks
  (:require [compojure.core :refer [defroutes POST]]
            [commiteth.github.core :as github]
            [commiteth.db.pull-requests :as pull-requests]
            [commiteth.db.issues :as issues]
            [commiteth.db.users :as users]
            [ring.util.http-response :refer [ok]]
            [clojure.string :refer [join]])
  (:import [java.util UUID]))

(def label-name "bounty")

(defn find-issue-closed-event
  [events]
  (first (filter #(= "closed" (:event %)) events)))

(defn handle-issue-closed
  [{{{user :login} :owner repo :name}   :repository
    {issue-id :id issue-number :number} :issue}]
  (future
    (->>
      (github/get-issue-events user repo issue-number)
      (find-issue-closed-event)
      (:commit_id)
      (issues/close issue-id))))

(defn get-commit-parents
  [commit]
  (->> commit :parents (map :sha) (join ",")))

(defn handle-pull-request-closed
  [{{{owner :login} :owner
     repo-name      :name
     repo-id        :id}                 :repository
    {{user-id :id
      login   :login
      name    :name}  :user
     id               :id
     merge-commit-sha :merge_commit_sha} :pull_request}]
  (future
    (->>
      (github/get-commit owner repo-name merge-commit-sha)
      (get-commit-parents)
      (hash-map :parents)
      (merge {:repo_id repo-id
              :pr_id   id
              :user_id user-id})
      (pull-requests/create))
    (users/create-user user-id login name nil nil)))

(defn labeled-as-bounty?
  [action issue]
  (and
    (= "labeled" action)
    (= label-name (get-in issue [:label :name]))))

(defn has-bounty-label?
  [issue]
  (let [labels (get-in issue [:issue :labels])]
    (some #(= label-name (:name %)) labels)))

(defn gen-address []
  (UUID/randomUUID))

(defn handle-issue
  [issue]
  (when-let [action (:action issue)]
    (when (labeled-as-bounty? action issue)
      (github/post-comment
        (get-in issue [:repository :owner :login])
        (get-in issue [:repository :name])
        (get-in issue [:issue :number]))
      (let [repo-id      (get-in issue [:repository :id])
            issue        (:issue issue)
            issue-id     (:id issue)
            issue-number (:number issue)]
        (issues/create repo-id issue-id issue-number (gen-address))))
    (when (and
            (= "closed" action)
            (has-bounty-label? issue))
      (handle-issue-closed issue)))
  (ok (str issue)))

(defn handle-pull-request
  [pull-request]
  (when (= "closed" (:action pull-request))
    (handle-pull-request-closed pull-request))
  (ok (str pull-request)))

(defroutes webhook-routes
  (POST "/webhook" {:keys [params headers]}
    (case (get headers "x-github-event")
      "issues" (handle-issue params)
      "pull_request" (handle-pull-request params)
      (ok))))
