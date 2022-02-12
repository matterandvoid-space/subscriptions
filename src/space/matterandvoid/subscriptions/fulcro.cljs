(ns space.matterandvoid.subscriptions.fulcro
  (:require
   [goog.object :as gobj]
   [taoensso.timbre :as log]
   [com.fulcrologic.fulcro.components :as c]))

(def reaction-key "space.matterandvoid.subscriptions.fulcro.reaction")

(defn setup-reaction [^clj reaction signals this render]
  (when (and
          (nil? (gobj/get this reaction-key))
          (seq (keep identity signals)))
    (log/info "SETTING Up reaction")
    (gobj/set this reaction-key reaction)
    (._update-watching reaction (into-array signals))
    (._set-opts reaction {:no-cache true})
    (let [prior-signal-values_ (atom (map deref signals))]
      (set! (.-f reaction) render)
      (set! (.-auto-run reaction)
        (fn []
          ;; fucking okay - this is working now - only this component will re-render when its subscriptions change
          ;; the next thing to do is only render once (only call RAF once
          (let [new-signal-values   (map deref signals)
                prior-signal-values @prior-signal-values_]
            (log/info ":test-value1: " (:test-value1 @(::app/state-atom (c/any->app this))))
            (log/info " IN AUTO RUN, new sig values: " new-signal-values)
            (log/info "ARE SIGS different? " (not= prior-signal-values new-signal-values))
            (log/info " NEW  sig values: " new-signal-values)
            (if (= new-signal-values prior-signal-values)
              (log/info "SIGNALS ARE NOT DIFFERENT")
              (do
                (log/info "!! SIGNALS ARE DIFFERENT")
                (reset! prior-signal-values_ new-signal-values)
                (if (exists? js/requestAnimationFrame)
                  (js/requestAnimationFrame (fn []
                                              (log/info "running test-item" (gobj/get this reaction-key))
                                              (.log js/console "IN RAF, refresh component: " this)
                                              (.log js/console "IN RAF, refresh component: " (c/get-ident this))
                                              (binding [c/*blindly-render* true] (c/refresh-component! this))))
                  (throw (js/Error. (str "'requestAnimationFrame' is not available in your environment!"))))))))))))
