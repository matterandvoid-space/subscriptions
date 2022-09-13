(ns space.matterandvoid.subscriptions.impl.hooks-common
  (:require
    ["react" :as react]
    ["react-dom" :as react-dom]
    ;["use-sync-external-store/shim" :refer [useSyncExternalStore]]
    ["use-sync-external-store/shim/with-selector" :refer [useSyncExternalStoreWithSelector]]
    [goog.object :as gobj]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.fulcro :as subs]))

(defn use-in-reaction
  "A react hook that takes a function with no arguments (a thunk) and runs the provided function inside a reagent `run-in-reaction`,
   returning the passed in function's value to the calling component. re-runs passed in function when any reagent reactive updates fire.

  The hook causes the consuming component to re-render at most once per frame even if the reactive callback fires more than
  once per frame."
  [deref-fn]
  (let [[output-value set-output-value!] (react/useState nil)
        render-scheduled? (react/useRef false)
        reaction-key      "reaction"
        reaction-obj      (react/useRef #js{})]
    (react/useEffect
      (fn setup-subscription []
        (let [return-val
              (ratom/run-in-reaction
                deref-fn
                (.-current reaction-obj)
                reaction-key
                (fn on-react! []
                  (when-not (.-current render-scheduled?)
                    (set! (.-current render-scheduled?) true)
                    (js/requestAnimationFrame
                      (fn [_]
                        (set-output-value! (deref-fn))
                        (set! (.-current render-scheduled?) false)))))
                {:no-cache true})]
          (set-output-value! return-val))
        (fn cleanup-subscription []
          (ratom/dispose! (gobj/get (.-current reaction-obj) reaction-key))))
      #js[])
    output-value))

;; The following was adapted from
;; https://github.com/roman01la/hooks/blob/1a98408280892da1abebde206b5ca2444aced1b3/src/hooks/impl.cljs

;; for more on implementation details see https://github.com/reactwg/react-18/discussions/86

(defn- setup-batched-updates-listener [^js ref]
  ;; Adding an atom holding a set of listeners on a ref if it wasn't added yet
  (when-not (.-react-listeners ref)
    (set! (.-react-listeners ref) (atom #{}))
    ;; When the ref is updated, execute all listeners in a batch
    (add-watch ref ::batched-subscribe
      (fn [_ _ _ _]
        (react-dom/unstable_batchedUpdates
          #(doseq [listener @(.-react-listeners ref)]
             (listener)))))))

(defn- teardown-batched-updates-listener [^js ref]
  ;; When the last listener is removed remove batched updates listener from the ref
  (when (empty? @(.-react-listeners ref))
    (set! (.-react-listeners ref) nil)
    (remove-watch ref ::batched-subscribe)))

(defn- use-batched-subscribe
  "Takes an atom-like ref type and returns a function that subscribes to changes
  in the ref, where subscribed listeners execution is batched via `react-dom/unstable_batchedUpdates`"
  [^js ref]
  (react/useCallback
    (fn [listener]
      (setup-batched-updates-listener ref)
      (swap! (.-react-listeners ref) conj listener)
      (fn []
        (swap! (.-react-listeners ref) disj listener)
        (teardown-batched-updates-listener ref)))
    #js[ref]))

(defn use-sync-external-store [subscribe get-snapshot]
  (useSyncExternalStoreWithSelector
    subscribe
    get-snapshot
    nil ;; getServerSnapshot, only needed for SSR
    identity ;; selector, not using, just returning the value itself
    =)) ;; value equality check

;; Public API

(defn use-reaction
  "Takes a Reagent Reaction and rerenders the UI component when the Reaction's value changes.
  Returns current value of the Reaction"
  [reaction]
  (let [subscribe    (use-batched-subscribe reaction)
        get-snapshot (react/useCallback (fn []
                                          ;; Mocking ratom context
                                          ;; This makes sure that watchers added to the `reaction`
                                          ;; will be triggered when the `reaction` gets updated.
                                          (ratom/in-reactive-context @reaction))
                       #js[reaction])]
    (use-sync-external-store subscribe get-snapshot)))
