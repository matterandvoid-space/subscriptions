(ns todo.fulcro.todo-app
  (:require
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.components :as c]
    [com.fulcrologic.fulcro.mutations :as mut :refer [defmutation]]
    [com.fulcrologic.fulcro.dom :as dom]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [defsc defsub reg-sub]]
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

;(subs/set-memoize! memoize-fn)
(subs/set-memoize! identity)

(defn make-todo
  ([text] (make-todo (random-uuid) text))
  ([id text] {:todo/id id :todo/text text :todo/state :incomplete}))

(defn make-comment
  ([text] (make-comment (random-uuid) text (js/Date.)))
  ([id text at] {:comment/id id :comment/text text :comment/at at}))

(defn toggle-todo [todo] (update todo :todo/state {:incomplete :complete :complete :incomplete}))

(comment
  (toggle-todo (toggle-todo (make-todo (random-uuid) "hi"))))

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


(reg-sub :todo/id (fn [_ {:todo/keys [id]}] (log/info ":todo/id " id) id))

(reg-sub :todo/text
  (fn [db {:todo/keys [id]}]
    (log/info ":todo/text")
    (get-in db [:todo/id id :todo/text])))

(reg-sub ::todo
  (fn [app args]
    (log/info "::todo inputs fn")
    {:todo/text (subs/subscribe app [:todo/text args])
     :todo/id   (subs/subscribe app [:todo/id args])})
  (fn [{:todo/keys [id] :as input}] (when id input)))

(defsub list-idents
  (fn [db {:keys [list-id] :as args}]
    (log/info "list -idents args: " args)
    (get db list-id)))

;; anytime you have a list of idents in fulcro the subscription pattern is to
;; have input signals that subscribe to layer 2 subscriptions

(reg-sub ::todos-list
  (fn [app {:keys [list-id] :as args}]
    (log/info "::todos-list args: " args)
    (let [todo-idents (list-idents app {:list-id list-id})]
      (mapv (fn [[_ i]] (subs/subscribe app [::todo {:todo/id i}])) todo-idents)))
  (fn [x]
    (log/info "::todos-list x: " x)
    x))

(reg-sub :todo/id2 :-> :root/todos)

(defmutation change-todo-text
  [{:keys [id text]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:todo/id id :todo/text] text)))

(defn change-todo-text! [this args] (c/transact! this [(change-todo-text args)]))

(defsc Todo [this {:todo/keys [text state completed-at]}]
  {:query         [:todo/id :todo/text :todo/state :todo/completed-at]
   :ident         :todo/id
   :initial-state (fn [text] (make-todo text))}
  (log/info "Rendering todo item: " text)
  (dom/div
    {}
    (dom/div "Todo:" (dom/div text))
    (dom/div "status: " (pr-str state))))

(def ui-todo (c/computed-factory Todo {:keyfn :todo/id}))

(defsc TodoList [this props]
  {:ident         (fn [] [:component/id ::todo-list])
   :query         [:list-id]
   ::subs/signals (fn [this {:keys [list-id]}]
                    (log/info "in todo list subs, list id: " list-id)
                    {:todos          [::todos-list {:list-id list-id}]
                     :complete-todos [::complete-todos {:list-id list-id}]})}
  (log/info "In TodoList render fn")
  (let [{:keys [todos complete-todos]} (subs/signals-map this)]
    (def t' todos)
    (dom/div
      (dom/h1 "Todos")
      (dom/pre (pr-str todos))
      (dom/hr)
      (map ui-todo todos))))

(def ui-todo-list (c/computed-factory TodoList))

(defsc Root [this {:root/keys [list-id]}]
  {:initial-state {:root/list-id :root/todos}
   :query         [:root/list-id]}
  (dom/div {} (ui-todo-list {:list-id list-id})))

(defonce fulcro-app (subs/fulcro-app {:initial-db {}}))

(defn ^:export ^:dev/after-load init [] (fulcro.app/mount! fulcro-app Root js/app))
(comment
  (macroexpand
   '(defsc Root [this {:root/keys [list-id]}]
     {:initial-state {:root/list-id :root/todos}
      :query         [:root/list-id]}
     (dom/div {} (ui-todo-list {:list-id list-id}))))
  )

(comment
  (def rr (r/make-reaction (fn [] (log/info "in reaction"))))
  (as-> fulcro-app XX
    ;(merge/merge-component! XX Todo (make-todo "helo"))
    (merge/merge-component! XX Todo (make-todo "helo29") :append [:root/todos])
    (fulcro.app/current-state fulcro-app))

  ;(swap! (::fulcro.app/state-atom fulcro-app) update :dan-num inc)
  ;;
  ;; okayyyyyyyyyyyyyyyyyyyy
  ;; this works
  (let [id (-> (fulcro.app/current-state fulcro-app) :todo/id keys first)]
    (change-todo-text! fulcro-app {:id id :text "XHANGEd8"}))

  ;; This will only work if the leaf component is rendered via a subscription
  ;; whereas if you use transact, you don't have to think about that
  ;(let [id (-> (fulcro.app/current-state fulcro-app) :todo/id keys first)]
  ;  (swap! (::fulcro.app/state-atom fulcro-app) assoc-in [:todo/id id :todo/text] "XHANGEd4"))

  (subs/<sub fulcro-app [::list-idents {:list-id :root/todos}])
  (subs/<sub fulcro-app [::todos-list {:list-id :root/todos}])

  (fulcro.app/current-state fulcro-app)
  (all-todos fulcro-app)
  (complete-todos fulcro-app)
  (incomplete-todos fulcro-app)
  (subs/<sub fulcro-app [:todo/id2])

  )
