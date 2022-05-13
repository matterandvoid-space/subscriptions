(ns todo.fulcro.todo-app
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.dom :as dom]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [defsc defsub reg-sub]]
    [com.fulcrologic.fulcro.components :as c]
    [reagent.ratom :as r]
    [taoensso.timbre :as log]))

(defn memoize-fn
  ([f] (memoize-fn {:max-args-cached-size 100 :max-history-size 50} f))
  ([{:keys [max-args-cached-size max-history-size]} f]
   (let [cache_          (atom {:args->data   {}
                                :args-history #queue[]})
         lookup-sentinel (js-obj)]
     (fn [& args]
       (println "memoized called with: " args)
       (let [{:keys [args-history args->data]} @cache_
             v (get args->data args lookup-sentinel)]
         (swap! cache_
           #(cond-> %
              ;; the size of the cache is limited by the total key-value pairs
              (and (= (count (keys args->data)) max-args-cached-size)
                (not (contains? args->data args)))
              ;; remove the oldest (LRU) argument
              ;; make room forthe new args->value pair
              (update :args->data dissoc (peek args-history))

              (= (count args-history) max-history-size) (update :args-history pop)

              ;; cache miss, assoc new kv pair
              (identical? v lookup-sentinel) ((fn [db]
                                                (println "Not cached, computing...")
                                                (update db :args->data assoc args (apply f args))))

              ;; save the args history
              true (update :args-history conj args)))
         (get (:args->data @cache_) args))))))

(subs/set-memoize! memoize-fn)

;; 1. make a fulcro app
;; 2. write the views
;; 3. write the mutations
;; 4. write the subs
;; that's it

(defn make-todo
  ([text] (make-todo (random-uuid) text))
  ([id text] {:todo/id id :todo/text text :todo/state :incomplete}))

(defn make-comment
  ([text] (make-comment (random-uuid) text (js/Date.)))
  ([id text at] {:comment/id id :comment/text text :comment/at at}))

(defn toggle-todo [todo] (update todo :todo/state {:incomplete :complete :complete :incomplete}))

(comment
  (toggle-todo
    (toggle-todo (make-todo (random-uuid) "hi"))))
(defsub all-todos :-> #(-> % :todo/id vals))
(defsub complete-todos :<- [::all-todos] :-> #(filter (comp #{:complete} :todo/state) %))
(defsub incomplete-todos :<- [::all-todos] :-> #(filter (comp #{:incomplete} :todo/state) %))

(defsc Comment [this {:comment/keys [text at]}]
  {:query         [:comment/id :comment/text :comment/at]
   :ident         :comment/id
   :initial-state (fn [text] (make-comment text))}
  (dom/div
    (dom/div "Comment: ") (dom/p text) (dom/p (str "at " (.toLocaleString at)))))

(def ui-comment (c/computed-factory Comment {:keyfn :comment/id}))

(defsc Todo [this {:todo/keys [text state completed-at]}]
  {:query         [:todo/id :todo/text :todo/state :todo/completed-at]
   :ident         :todo/id
   :initial-state (fn [text] (make-todo text))}
  (dom/div
    {}
    "Todo:" (dom/div text)
    (dom/div "status: " (pr-str state))))

(def ui-todo (c/computed-factory Todo {:keyfn :todo/id}))

;; todo copy the example from the fulcro codebase - where a list has an id
;; this way you can use 'this' in the subscription to get the current value of the list
;; when computing a subscription and to maintain multiple lists.
;; with filtering sorting etc handled by one parameterized sub.


;; so i'm thinking in this demo all data that is _read_ to draw the UI comes thru subscriptions
;; the defsc and queries and idents are use for normalization.
;; don't try to please people using fulcro for other purposes

;; so these sort of subscriptions can be add to defsc macro

(reg-sub :todo/id (fn [_ {:todo/keys [id]}]
                    (log/info ":todo/id")
                    id))

(reg-sub :todo/text
  (fn [db {:todo/keys [id]}]
    (log/info ":todo/text")
    (get-in db [:todo/id id :todo/text])))

;(reg-sub ::habit/record-log-idents
;  (fn [db [_ {::habit/keys [id]}]]
;    (get-in db (habit/ident id ::habit/record-log))))
;
;(reg-sub ::habit/record-log
;  (fn [[_ args]]
;    ;(log/info "computing signals for ::habit/record-log")
;    {::habit/record-log (mapv #(sub/subscribe SPA [::record-log.subs/record-log (apply hash-map %)])
;                              (sub/<sub SPA [::habit/record-log-idents args]))})
;  (fn [{::habit/keys [record-log]} [_ {::habit/keys [id]}]]
;    ;(log/info "computing record log: " record-log)
;    record-log))
(reg-sub ::todo
  (fn [app args]
    (log/info "::todo inputs fn")
    ;(log/info "signas, input: " args)
    ;; so this is the structure to end up with
    ;; it's a tree that mirrors the query - at this level you don't care if a piece of data is a join or not
    ;; the implementation of the subscription will deal with that
    ;; for example - fetching all the record-logs
    {:todo/text (subs/subscribe app [:todo/text args])
     :todo/id   (subs/subscribe app [:todo/id args])})
  (fn [{:todo/keys [id] :as input}]
    (log/info "::todo")
    (when id
      ;(habit/make-habit input)
      input)
    ))
(comment
  (swap! (::fulcro.app/state-atom fulcro-app) assoc-in [:todo/id #uuid"703ecd9c-1ebe-47e2-8ed0-adad1f2642de" :todo/text] "Changed")
  (subs/<sub fulcro-app [::todo {:todo/id #uuid"703ecd9c-1ebe-47e2-8ed0-adad1f2642de"}]))

(defsc TodoList [this props]
  {:ident         (fn [] [:component/id ::todo-list])
   :query         [:list-id]
   ::subs/signals (fn [this {:keys [list-id]}]
                    {:todos          [::todos-list {:list-id list-id}]
                     :complete-todos [::complete-todos {:list-id list-id}]})}
  (let [{:keys [todos complete-todos]} (subs/signals-map this)]
    (dom/div
      (dom/h1 "Todos")
      (map ui-todo todos))))

(def ui-todo-list (c/computed-factory TodoList))

;(defsc TodoList [this {:list/keys [id items filter title] :as props}]
;  {:initial-state {:list/id 1 :ui/new-item-text "" :list/items [] :list/title "main" :list/filter :list.filter/none}
;   :ident         :list/id
;   :query         [:list/id :ui/new-item-text {:list/items (comp/get-query TodoItem)} :list/title :list/filter]}
;  (let [num-todos       (count items)
;        completed-todos (filterv :item/complete items)
;        num-completed   (count completed-todos)
;        all-completed?  (every? :item/complete items)
;        filtered-todos  (case filter
;                          :list.filter/active (filterv (comp not :item/complete) items)
;                          :list.filter/completed completed-todos
;                          items)
;        delete-item     (fn [item-id] (comp/transact! this `[(api/todo-delete-item ~{:list-id id :id item-id})]))]
;    (dom/div {}
;      (dom/section :.todoapp {}
;        (header this title)
;        (when (pos? num-todos)
;          (dom/div {}
;            (dom/section :.main {}
;              (dom/input {:type      "checkbox"
;                          :className "toggle-all"
;                          :checked   all-completed?
;                          :onClick   (fn [] (if all-completed?
;                                              (comp/transact! this `[(api/todo-uncheck-all {:list-id ~id})])
;                                              (comp/transact! this `[(api/todo-check-all {:list-id ~id})])))})
;              (dom/label {:htmlFor "toggle-all"} "Mark all as complete")
;              (dom/ul :.todo-list {}
;                (map #(ui-todo-item % {:delete-item delete-item}) filtered-todos)))
;            #_(filter-footer this num-todos num-completed))))
;      #_(footer-info))))

;(def ui-todo-list (c/factory TodoList))

;(defsc Root [this {:keys [todo-list]}]
;  {:query [{:todo-list (c/get-query TodoList)}]}
;  (dom/div "App:"
;    (ui-todo-list todo-list)))

(defsc Root [this {:root/keys [list-id]}]
  {:initial-state {:root/list-id :root/todos}
   :query         [:root/list-id]}
  (dom/div {} (ui-todo-list {:list-id list-id})))

(make-todo "one")
(make-todo "two")
(make-todo "three")
(make-todo "four")
(make-todo "five")
(defonce fulcro-app (subs/fulcro-app {:initial-db {}}))
(defn ^:export ^:dev/after-load init [] (fulcro.app/mount! fulcro-app Root js/app))


;; anytime you have a list of idents in fulcro the subscription pattern is to
;; have input signals that subscribe to layer 2 subscriptions


;; db:
{:root/todos [[:todo/id #uuid"43615860-c746-4891-be66-aeb3c2b4569c"]
              [:todo/id #uuid"703ecd9c-1ebe-47e2-8ed0-adad1f2642de"]
              [:todo/id #uuid"db9b8642-2781-42a7-97eb-1f6ed8262834"]
              [:todo/id #uuid"eba2982b-93c6-4ef5-910d-397054dfa020"]]}



;; idea:
;; change design of the library to be:
;; args passed to an input signals function is always: <storage> <args-map>

;; passed to subscribe is always: (subscribe <storage> <keyword> <args-map>?)
;; what about in the syntax:

;; I think the 2-tuple is the way to go actually. But you can optimize
;; don't need to pass the event name to the handlers and input signals fn.
;(reg-sub ::abc :<- [::subscibe {:args 1}] :<- ::other (fn [db] ) )
;(reg-sub ::abc :<- ::subscibe {:args 1} :<- ::other (fn [db] ) )

;; never pass the query kw

(defsub list-idents (fn [db {:keys [list-id] :as args}]
                      (log/info "list -idents args: " args)
                      (get db list-id)))

;(reg-sub ::todos-list
;  (fn [app {:keys [list-id]}]
;    (.log js/console "HELLO")
;    (log/info "todos list list id : " list-id)
;    (let [todo-idents (list-idents app {:list-id list-id})]
;      (mapv (fn [[_ i]] (subs/subscribe app [::todo {:todo/id i}])) todo-idents)))
;  (fn [x]
;    (log/info "::todos-list x: " x)
;    x))

(reg-sub ::todos-list
  (fn INPUT [app {:keys [list-id] :as args}]
    (log/info "::todos-list args: " args)
    (let [todo-idents (list-idents app {:list-id list-id})]
      (log/info "todo idents: " todo-idents)
      (mapv (fn [[_ i]] (subs/subscribe app [::todo {:todo/id i}])) todo-idents))
    )
  (fn [x]
    (log/info "::todos-list x: " x)
    x))

(reg-sub :todo/id2 :-> :root/todos)

(comment
  (as-> fulcro-app XX
    ;(merge/merge-component! XX Todo (make-todo "helo"))
    (merge/merge-component! XX Todo (make-todo "helo19") :append [:root/todos])
    (fulcro.app/current-state fulcro-app))

  (swap! (::fulcro.app/state-atom fulcro-app) update :dan-num inc)
  ;;
  (let [id  #uuid"18ee1d24-6d71-4e49-927f-8fc04b42ce03"]
    (swap! (::fulcro.app/state-atom fulcro-app) assoc-in [:todo/id id :todo/text]  "CHANGEd"))

  (fulcro.app/current-state fulcro-app)
  (all-todos fulcro-app)
  (complete-todos fulcro-app)
  (incomplete-todos fulcro-app)
  (subs/<sub fulcro-app [:todo/id2])

  (subs/<sub fulcro-app [::list-idents {:list-id :root/todos}])
  (subs/<sub fulcro-app [::todos-list {:list-id :root/todos}])
  )
(def rr
  (r/make-reaction (fn [] (log/info "in reaction"))))
(comment
  )
