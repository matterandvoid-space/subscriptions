(ns space.matterandvoid.subscriptions.impl.react-hooks-common
  (:require
    ["react" :as react]
    ["react-dom" :as react-dom]
    ["use-sync-external-store/shim" :refer [useSyncExternalStore]]
    ["use-sync-external-store/shim/with-selector" :refer [useSyncExternalStoreWithSelector]]
    [goog.object :as gobj]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]))

;; The following was adapted from
;; https://github.com/roman01la/hooks/blob/1a98408280892da1abebde206b5ca2444aced1b3/src/hooks/impl.cljs

;; for more on implementation details see https://github.com/reactwg/react-18/discussions/86

(defn use-run-in-reaction [reaction]
  (let [reaction-key      "reaction"
        reaction-obj      (react/useRef #js{})]
    (react/useCallback
      (fn setup-subscription [listener]
        (ratom/run-in-reaction
          (fn [] (when reaction @reaction))
          (.-current reaction-obj)
          reaction-key
          listener
          {:no-cache true})
        (fn cleanup-subscription []
          (when (gobj/get (.-current reaction-obj) reaction-key)
            (ratom/dispose! (gobj/get (.-current reaction-obj) reaction-key)))))
      #js [reaction])))

(defn use-sync-external-store [subscribe get-snapshot]
  ;; https://reactjs.org/docs/hooks-reference.html#usesyncexternalstore
  ;; this version uses useMemo to avoid rerenders when the snapshot is the same across renders.
  (useSyncExternalStoreWithSelector
    subscribe
    get-snapshot
    get-snapshot ;; getServerSnapshot, only needed for SSR ;; todo need to test this
    identity ;; selector, not using, just returning the value itself
    =)) ;; value equality check

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
        subscribe    (use-run-in-reaction reaction)
        get-snapshot (react/useCallback (fn [] (ratom/in-reactive-context #js{} (fn [] (when reaction @reaction)))) #js[reaction])]
    (use-sync-external-store subscribe get-snapshot)))

(defn use-reaction
  "Takes a Reagent Reaction and rerenders the UI component when the Reaction's value changes.
   Returns the current value of the Reaction"
  [^clj reaction]
  (let [subscribe    (use-run-in-reaction reaction)
        get-snapshot (react/useCallback (fn [] (ratom/in-reactive-context #js{} (fn [] (when reaction @reaction)))) #js[reaction])]
    (use-sync-external-store subscribe get-snapshot)))
