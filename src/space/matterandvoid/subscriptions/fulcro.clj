(ns space.matterandvoid.subscriptions.fulcro)


;(defmacro defsc [])


(defsc TestSignals
  [this {:task/keys [id description], :ui/keys [show-debug?] :as props}]
  {:query          []
   :ident          (fn [_ props] [:component/id ::test-item])
   ::subscriptions [[::habit.subs/test-value1-1]]}
  (let [my-data (sub/<sub this [::habit.subs/test-value1-1])]
    (log/info "RENDERING TEST ITEM")
    (dom/div :.ui.segment
      (dom/div "THIS IS TEST ITEM")
      (dom/div "data: ::habit.subs/test-value1-1---"
        my-data)))
  (setup-reaction reaction signals this client-render)
  (client-render))


;; desired output:
;; target defsc
;
;; in the macro:
;; pull off subscriptions from the component
;; setup (setup-reaction -> this happens in render)
;; then pass the client-s


(defsc TestSignals
  [this {:task/keys [id description], :ui/keys [show-debug?] :as props}]
  {:query          []
   :ident          (fn [_ props] [:component/id ::test-item])
   ;; the idea here is that you could have a subscription that depends on props or the component instance
   ::subscriptions (fn [this props]
                     {:my-data [::habit.subs/test-value1-1]})}
  ;; this way you're not repeating the subscription logic - that happens up above
  ;; or you could wrap the render function in a let
  ;; (let [{:keys [:my-data]} {:my-data (sub/<sub [::habit.subs/test-value1-1])}]
  ;; }
  (let [my-data (helpers/get-subs :my-data)]
    (log/info "RENDERING TEST ITEM")
    (dom/div :.ui.segment
      (dom/div "THIS IS TEST ITEM")
      (dom/div "data: ::habit.subs/test-value1-1---"
        my-data))))
