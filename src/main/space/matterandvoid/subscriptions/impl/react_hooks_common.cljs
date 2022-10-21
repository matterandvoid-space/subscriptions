(ns space.matterandvoid.subscriptions.impl.react-hooks-common
  (:require
    ["react" :as react]
    ["react-dom" :as react-dom]
    ["use-sync-external-store/shim" :refer [useSyncExternalStore]]
    ["use-sync-external-store/shim/with-selector" :refer [useSyncExternalStoreWithSelector]]
    [goog.object :as gobj]
    [taoensso.timbre :as log]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]))

;; The following was adapted from
;; https://github.com/roman01la/hooks/blob/1a98408280892da1abebde206b5ca2444aced1b3/src/hooks/impl.cljs

;; for more on implementation details see https://github.com/reactwg/react-18/discussions/86

(defn- setup-batched-updates-listener [^clj reaction]
  ;; Adding an atom holding a set of listeners on a ref if it wasn't added yet
  (when-not (.-react-listeners reaction)
    (set! (.-react-listeners reaction) (atom #{}))
    ;; When the ref is updated, execute all listeners in a batch
    (add-watch reaction ::batched-subscribe
      (fn [_ _ _ _]
        (react-dom/unstable_batchedUpdates
          #(doseq [listener @(.-react-listeners reaction)]
             (listener)))))))

(defn- teardown-batched-updates-listener [^clj ref]
  ;; When the last listener was removed,
  ;; remove batched updates listener from the ref
  (when (empty? @(.-react-listeners ref))
    (set! (.-react-listeners ref) nil)
    (remove-watch ref ::batched-subscribe)))

(defn use-batched-subscribe
  "Takes an atom-like ref type and returns a function that subscribes to changes
  in the ref, where subscribed listeners execution is batched via `react-dom/unstable_batchedUpdates`"
  [^clj reaction]
  (react/useCallback
    (fn add-listener [listener]
      (setup-batched-updates-listener reaction)
      (swap! (.-react-listeners reaction) conj listener)
      (fn remove-listener []
        (swap! (.-react-listeners reaction) disj listener)
        (teardown-batched-updates-listener reaction)))
    #js [reaction]))


(defn use-sync-external-store [subscribe get-snapshot]
  ;; https://reactjs.org/docs/hooks-reference.html#usesyncexternalstore
  ;; this version uses useMemo to avoid rerenders when the snapshot is the same across renders.
  (useSyncExternalStoreWithSelector
    subscribe
    get-snapshot
    get-snapshot ;; getServerSnapshot, only needed for SSR ;; todo need to test this
    identity ;; selector, not using, just returning the value itself
    =)) ;; value equality check

(defn use-run-in-reaction [reaction get-snapshot]
  (let [reaction-key "reaction"
        reaction-obj (react/useRef #js{})]
    (react/useCallback
      (fn setup-subscription [listener]
        (ratom/run-in-reaction
          get-snapshot
          (.-current reaction-obj)
          ;reaction-obj
          reaction-key
          listener
          {:no-cache true})
        (fn cleanup-subscription []
          (log/debug "CLEANUP sub" (.-current reaction-obj))
          (when (gobj/get (.-current reaction-obj) reaction-key)
            (log/debug "disposing run-in-reaction reaction")
            (ratom/dispose! (gobj/get (.-current reaction-obj) reaction-key)))))
      #js [reaction])))

;; Public API

(defn use-reaction-ref
  "Takes a Reagent Reaction inside a React Ref and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  [^js reaction-ref]
  (when goog/DEBUG
    (when (not (gobj/containsKey reaction-ref "current"))
      (throw (js/Error (str "use-reaction hook must be passed a reaction inside a React ref."
                         " You passed: " (pr-str reaction-ref))))))

  (let [reaction     (.-current reaction-ref)
        get-snapshot (react/useCallback (fn [] (ratom/in-reactive-context #js{} (fn [] (when reaction @reaction)))) #js[reaction])
        subscribe    (use-run-in-reaction reaction get-snapshot)]
    (use-sync-external-store subscribe get-snapshot)))

(defn use-reaction-old
  "Takes a Reagent Reaction and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  [^clj reaction]
  (let [get-snapshot (react/useCallback (fn [] (when reaction @reaction)) #js[reaction])
        subscribe    (use-run-in-reaction reaction get-snapshot)]
    (use-sync-external-store subscribe get-snapshot)))

(defn use-reaction
  "Takes Reagent's Reaction or RCursor, subscribes the UI component to changes in the Reaction and returns current state value
  of the Reaction"
  [^clj reaction]
  (assert (or (ratom/reaction? reaction) (ratom/cursor? reaction))
    "reaction should be an instance of reagent.ratom/Reaction or reagent.ratom/RCursor")
  (let [subscribe    (use-batched-subscribe reaction)
        get-snapshot (react/useCallback (fn []
                                          ;; Mocking ratom context
                                          ;; This makes sure that watchers added to the `reaction`
                                          ;; will be triggered when the `reaction` gets updated.
                                          (ratom/in-reactive-context #js {} (fn [] @reaction)))
                       #js [reaction])]
    (use-sync-external-store subscribe get-snapshot)))
