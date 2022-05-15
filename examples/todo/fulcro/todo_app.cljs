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

(subs/set-memoize! memoize-fn)
;(subs/set-memoize! identity)

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


(reg-sub :todo/id (fn [_ {:todo/keys [id]}]
                    (log/info ":todo/id subscription comp fn " id) id))

(reg-sub :todo/text
  (fn [db {:todo/keys [id]}]
    (log/info "IN ::todo/text sub computation fn")
    (get-in db [:todo/id id :todo/text])))

(reg-sub ::todo
  (fn [app args]
    (log/info "IN ::todo sub inputs fn")
    {:todo/text (subs/subscribe app [:todo/text args])
     :todo/id   (subs/subscribe app [:todo/id args])})
  (fn [{:todo/keys [id] :as input}]
    (log/info "IN ::todo sub computation fn")
    (when id input)))

(defsub list-idents
  (fn [db {:keys [list-id] :as args}]
    (log/info "list-idents subscription args: " args)
    (log/info "return valu: " (get db list-id))
    (get db list-id)))

;; anytime you have a list of idents in fulcro the subscription pattern is to
;; have input signals that subscribe to layer 2 subscriptions

(reg-sub ::todo-table :-> (fn [db] (-> db :todo/id)))

(reg-sub ::todo-list-expanded
  (fn [app {:keys [list-id] :as args}]
    (log/info "IN ::todos-list inputs fn: " args)
    [(subs/subscribe app [::list-idents {:list-id list-id}])
     (subs/subscribe app [::todo-table])])
  (fn [[idents table]]
    (log/info "subsc ::todo-list-expanded idents" idents)
    (log/info "subsc ::todo-list-expanded table" table)
    (mapv #(get table (second %)) idents)))

(defsub todos-list
  (fn [app {:keys [list-id] :as args}]
    (log/info "IN ::todos-list inputs fn: " args)
    (subs/subscribe app [::todo-list-expanded args]))
  (fn [x]
    (log/info "IN ::todos-list computation fn: " x)
    x))

(reg-sub :todo/id2 :-> :root/todos)

(defmutation change-todo-text
  [{:keys [id text]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:todo/id id :todo/text] text)))

(defn change-todo-text! [this args] (c/transact! this [(change-todo-text args)]))

(defsc Todo [this {:todo/keys [text state completed-at]}]
  {:query                [:todo/id :todo/text :todo/state :todo/completed-at]
   :ident                :todo/id
   :componentWillUnmount (fn [this]
                           (log/info "TODO UNMOUNTING"))
   :initial-state        (fn [text] (make-todo text))}
  (log/info "Rendering todo item: " text)
  (dom/div
    {}
    (dom/div "Todo:" (dom/div text))
    (dom/div "status: " (pr-str state))))

(comment
  (macroexpand
    '(defsc Todo [this {:todo/keys [text state completed-at]}]
       {:query                [:todo/id :todo/text :todo/state :todo/completed-at]
        :ident                :todo/id
        :componentWillUnmount (fn [this]
                                (log/info "TODO UNMOUNTING"))
        :initial-state        (fn [text] (make-todo text))}
       (log/info "Rendering todo item: " text)
       (dom/div
         {}
         (dom/div "Todo:" (dom/div text))
         (dom/div "status: " (pr-str state))))
    )
  )

(def ui-todo (c/computed-factory Todo {:keyfn :todo/id}))

(defsub todos-total (fn [app args]
                      (subs/subscribe app [::todos-list args]))
  (fn [todos] (count todos)))

(defsc TodosTotal [this {:keys [list-id]}]
  {:query [:list-id]}
  (dom/h3 "Total todos: " (todos-total this {:list-id list-id})))

(def ui-todos-total (c/factory TodosTotal))

(defsc TodoList [this {:keys [list-id]}]
  {:ident (fn [] [:component/id ::todo-list])
   :query [:list-id]}
  (log/info "In TodoList render fn")
  (let [todos (todos-list this {:list-id list-id})]
    ;(def t' todos)
    (dom/div {}
      (dom/h1 "Todos")
      (ui-todos-total {:list-id list-id})
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
    (merge/merge-component! XX Todo (make-todo "helo429") :append [:root/todos])
    (fulcro.app/current-state fulcro-app))

  ;(swap! (::fulcro.app/state-atom fulcro-app) update :dan-num inc)
  ;;
  ;; okayyyyyyyyyyyyyyyyyyyy
  ;; this works
  (let [id (-> (fulcro.app/current-state fulcro-app) :todo/id keys first)]
    (change-todo-text! fulcro-app {:id id :text "199XHANGEd8"}))

  ;; This will only work if the leaf component is rendered via a subscription
  ;; whereas if you use transact, you don't have to think about that
  ;(let [id (-> (fulcro.app/current-state fulcro-app) :todo/id keys first)]
  ;  (swap! (::fulcro.app/state-atom fulcro-app) assoc-in [:todo/id id :todo/text] "22XHANGEd4"))

  (subs/<sub fulcro-app [::list-idents {:list-id :root/todos}])
  (subs/<sub fulcro-app [::todos-list {:list-id :root/todos}])

  (fulcro.app/current-state fulcro-app)
  (keys fulcro-app)
  (:com.fulcrologic.fulcro.application/algorithms fulcro-app)
  (all-todos fulcro-app)
  (complete-todos fulcro-app)
  (incomplete-todos fulcro-app)
  (subs/<sub fulcro-app [:todo/id2])

  )


(def base-val (r/atom 0))
(def reaction-one (r/make-reaction (fn [] (log/info "in reaction one")
                                     (inc @base-val))))

(def reaction-two (r/make-reaction (fn [] (log/info "in reaction two") (+ 10 @reaction-one))))
(def reaction-three (r/make-reaction (fn [] (log/info "in reaction three") (+ 10 @reaction-two))))

(comment
  @base-val

  (let [js-val #js{}]
    (r/run-in-reaction
      (fn []
        (log/info "the value of reaction 3: " @reaction-three)
        (+ @reaction-three 10)
        )
      js-val
      "reaction_key"
      (fn [new-js-val] (log/info "IN reactive RUN"))
      nil
      ))

  @reaction-three
  (swap! base-val inc))

(def r (r/make-reaction (fn []
                          (log/info "In reaction r")
                          500
                          )))

(def temp-reaction (r/make-reaction nil))
(def res (r/deref-capture (fn []
                            (log/info "in the func")
                            @r
                            )
           temp-reaction
           ))

(def x
  (r/->Reaction
    (fn []
      (log/info "In reaction") 500)
    :state ;; state
    true ;; dirty?
    false ;; nocache?
    nil ; watching
    nil ; watches
    nil ;autorun
    nil ; caught
    ))
(comment
  (.-state x)
  (deref x)
  (deref r))
