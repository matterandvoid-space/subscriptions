(ns space.matterandvoid.subscriptions.impl.react-hooks-common
  (:require-macros [space.matterandvoid.subscriptions.impl.react-hooks-common])
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
      (when reaction
        ;(log/info "Adding listeners in use batched subscribe " (pr-str reaction))
        (setup-batched-updates-listener reaction)
        (swap! (.-react-listeners reaction) conj listener))
      (fn remove-listener []
        (when reaction
          (swap! (.-react-listeners reaction) disj listener)
          (teardown-batched-updates-listener reaction))))
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

(defn use-run-in-reaction [reaction]
  (let [reaction-key "reaction"
        reaction-obj (react/useRef #js{})]
    (react/useCallback
      (fn setup-subscription [listener]
        (ratom/run-in-reaction
          (fn [] (when reaction @reaction))
          (.-current reaction-obj)
          ;reaction-obj
          reaction-key
          listener
          {:no-cache true})
        (fn cleanup-subscription []
          ;(log/debug "CLEANUP sub" (.-current reaction-obj))
          (when (gobj/get (.-current reaction-obj) reaction-key)
            ;(log/debug "disposing run-in-reaction reaction")
            (ratom/dispose! (gobj/get (.-current reaction-obj) reaction-key)))))
      #js [reaction])))

;; Public API

(defn use-reaction2
  "Takes a Reagent Reaction and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  [^clj reaction]
  (let [get-snapshot (react/useCallback (fn [] (ratom/in-reactive-context (when reaction @reaction)))
                       #js[reaction])
        subscribe    (use-run-in-reaction reaction)]
    (use-sync-external-store subscribe get-snapshot)))

(defn use-reaction
  "Takes Reagent's Reaction or RCursor, subscribes the UI component to changes in the Reaction and returns current state value
  of the Reaction"
  [^clj reaction]
  (assert (or (ratom/reaction? reaction) (ratom/cursor? reaction) (nil? reaction))
    "reaction should be an instance of reagent.ratom/Reaction or reagent.ratom/RCursor")
  (let [subscribe    (use-batched-subscribe reaction)
        get-snapshot (react/useCallback (fn [] (ratom/in-reactive-context (when reaction @reaction)))
                       #js [reaction])]
    (use-sync-external-store subscribe get-snapshot)))

(defn use-sub
  [reactive-subscribe datasource query equal?]
  (let [last-query (react/useRef query)
        ref        (react/useRef nil)]

    (when-not (.-current ref)
      (set! (.-current ref)
        (reactive-subscribe datasource query)))

    (when-not (equal? (.-current last-query) query)
      (set! (.-current last-query) query)
      (ratom/dispose! (.-current ref))
      (set! (.-current ref)
        (reactive-subscribe datasource query)))

    (react/useEffect
      (fn mount []
        (fn unmount [] (when (.-current ref) (ratom/dispose! (.-current ref)))))
      #js[])

    (use-reaction (.-current ref))))
