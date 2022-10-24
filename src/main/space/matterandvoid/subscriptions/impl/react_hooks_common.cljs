(ns space.matterandvoid.subscriptions.impl.react-hooks-common
  (:require-macros [space.matterandvoid.subscriptions.impl.react-hooks-common])
  (:require
    ["react" :as react]
    ["react-dom" :as react-dom]
    ["use-sync-external-store/shim" :refer [useSyncExternalStore]]
    ["use-sync-external-store/shim/with-selector" :refer [useSyncExternalStoreWithSelector]]
    [goog.object :as gobj]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    [taoensso.timbre :as log]))

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
  (when (and (.-react-listeners ref) (empty? @(.-react-listeners ref)))
    (set! (.-react-listeners ref) nil)
    ;; We do not remove the watch here, instead we dispose the subscription when the component unmounts
    ;(remove-watch ref ::batched-subscribe)
    ))

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
          ;; We do not remove the watch here, instead we dispose the subscription when the component unmounts
          ;(teardown-batched-updates-listener reaction)
          )))
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

(defn use-run-in-reaction [reaction cleanup?]
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
          (when (and cleanup? (gobj/get (.-current reaction-obj) reaction-key))
            (log/debug "disposing run-in-reaction reaction")
            (ratom/dispose! (gobj/get (.-current reaction-obj) reaction-key)))))
      #js [reaction])))

;; Public API

(defn use-reaction
  "Takes a Reagent Reaction and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  ([^clj reaction]
   (use-reaction reaction true))

  ([^clj reaction cleanup?]
   (assert (or (ratom/reaction? reaction) (ratom/cursor? reaction) (nil? reaction))
     "reaction should be an instance of reagent.ratom/Reaction or reagent.ratom/RCursor")
   (let [get-snapshot (react/useCallback (fn [] (ratom/in-reactive-context (when reaction @reaction)))
                        #js[reaction])
         subscribe    (use-run-in-reaction reaction cleanup?)]
     (use-sync-external-store subscribe get-snapshot))))

;; this version does not work when watching Cursors
(defn use-reaction2
  "Takes Reagent's Reaction or RCursor, subscribes the UI component to changes in the Reaction and returns current state value
  of the Reaction"
  [^clj reaction]
  (assert (or (ratom/reaction? reaction) (ratom/cursor? reaction) (nil? reaction))
    "reaction should be an instance of reagent.ratom/Reaction or reagent.ratom/RCursor")
  (let [subscribe    (use-batched-subscribe reaction)
        get-snapshot (react/useCallback (fn [] (ratom/in-reactive-context (when reaction @reaction)))
                       #js [reaction])]
    (use-sync-external-store subscribe get-snapshot)))

;; The subscription hook uses a React Ref to wrap the Reaction.
;; The reason for doing so is so that React does not re-create the Reaction object each time the component is rendered.
;;
;; This is safe because the ref's value never changes for the lifetime of the component (per use of use-reaction)
;; Thus the caution to not read .current from a ref during rendering doesn't apply because we know it never changes.
;;
;; The guideline exists for refs whose underlying value will change between renders, but we are just using it
;; as a cache local to the component in order to not recreate the Reaction with each render.
;;
;; References:
;; - https://beta.reactjs.org/apis/react/useRef#referencing-a-value-with-a-ref
;; - https://beta.reactjs.org/apis/react/useRef#avoiding-recreating-the-ref-contents

(defn use-sub
  [subscribe datasource query equal?]
  ;; We save every subscription the component uses while mounted and then dispose them all at once.
  ;; this way if a query changes and a new subscription is used we don't evict that subscription from the cache until the
  ;; component unmounts.
  (let [last-query (react/useRef query)
        ref        (react/useRef nil)
        subs-log   (react/useRef #js[])]

    (when-not (.-current ref)
      (let [sub (ratom/in-reactive-context (subscribe datasource query))]
        (.push (.-current subs-log) sub)
        (set! (.-current ref) sub)))

    (when-not (equal? (.-current last-query) query)
      (set! (.-current last-query) query)
      (let [sub (ratom/in-reactive-context (subscribe datasource query))]
        (.push (.-current subs-log) sub)
        (set! (.-current ref) sub)))

    (react/useEffect
      (fn mount []
        (fn unmount []
          (doseq [q (.-current subs-log)]
            (when q (ratom/dispose! q)))))
      #js[])

    (use-reaction (.-current ref) false)))
