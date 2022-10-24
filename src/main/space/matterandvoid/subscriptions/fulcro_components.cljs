(ns space.matterandvoid.subscriptions.fulcro-components
  (:require-macros [space.matterandvoid.subscriptions.fulcro])
  (:require
    [com.fulcrologic.fulcro.algorithms.indexing :as fulcro.index]
    [com.fulcrologic.fulcro.algorithm :as-alias fulcro.algo]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.components :as c]
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as ident-optimized-render]
    [space.matterandvoid.subscriptions.impl.fulcro :as impl]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]
    ["react" :as react]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reactive refresh of components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cleanup! "Intended to be called when a component unmounts to clear the registered Reaction."
  [this] (impl/cleanup! this))

(defn setup-reaction!
  "Installs a Reaction on the provided component which will re-render the component when any of the subscriptions'
   values change.
   Takes a component instance and a render function with signature: (fn render [this])"
  [this client-render] (impl/setup-reaction! this client-render))

(defn with-reactive-subscriptions
  "Takes a fulcro app and adds support for using subscriptions
    The components which deref subscriptions in their render bodies will be refreshed when those subscriptions change,
    separate from the fulcro rendering mechanism.
  - Adds render middleware to run-in-reaction for class components
  - Adds cleanup when a component is unmounted
  - Changes the state atom to be a reagent.ratom/atom
  - Changes the `optimized-render! algorithm to be the ident-optmized-render algorithm."
  [app]
  (-> app
    (assoc ::fulcro.app/state-atom (ratom/atom @(::fulcro.app/state-atom app)))
    (update ::fulcro.app/algorithms
      assoc
      ::fulcro.algo/optimized-render! ident-optimized-render/render!

      ::fulcro.algo/render-middleware
      (fn [this render-fn]
        (let [final-render-fn
              (if-let [middleware (::fulcro.algo/render-middleware app)]
                (fn [] (middleware this render-fn))
                render-fn)]
          (if-let [^clj reaction (impl/get-component-reaction this)]
            (do
              (when goog/DEBUG
                ;; deals with hot reloading when the render function body changes
                (set! (.-f reaction) final-render-fn))
              (._run reaction false))
            (setup-reaction! this final-render-fn))))

      ::fulcro.algo/drop-component!
      (fn drop-component-middleware
        ([this]
         ;(log/info "Drop component!" (c/component-name this))
         (cleanup! this)
         (fulcro.index/drop-component! this))
        ([this ident]
         ;(log/info "Drop component!" (c/component-name this))
         (cleanup! this)
         (fulcro.index/drop-component! this ident))))))

;; TODO this is currently not fully implemented.
(defn with-subscriptions
  "Takes a fulcro app and adds support for using subscriptions
  With this version of subscriptions integration with fulcro the intention is that fulcro manages all rendering and
  subscriptions do not trigger reactive updates - instead they are executed after each fulcro transcation and swap! into the
  fulcro state atom the derived state that the subscriptions computed (the subscription cache).

  - Adds render middleware to run-in-reaction for class components
  - Adds cleanup when a component is unmounted
  - Changes the state atom to be a reagent.ratom/atom
  - Changes the `optimized-render! algorithm to be the ident-optmized-render algorithm."
  [app]
  (-> app
    (assoc ::fulcro.app/state-atom (ratom/atom @(::fulcro.app/state-atom app)))
    (update ::fulcro.app/algorithms
      assoc
      ::fulcro.algo/optimized-render! ident-optimized-render/render!

      ::fulcro.algo/before-render (fn [app root-class]
                                    ;(log/info "in before-render")
                                    (impl/subs-cache->fulcro-app-state app))

      ::fulcro.algo/render-middleware
      (fn [this render-fn]
        ;(log/info "in render middleware")
        (let [final-render-fn
              (if-let [middleware (::fulcro.algo/render-middleware app)]
                (fn [] (middleware this render-fn))
                render-fn)]
          ;(log/info "in render middleware")
          (if-let [^clj reaction (impl/get-component-reaction this)]
            (do
              (when goog/DEBUG
                ;; deals with hot reloading when the render function body changes
                (set! (.-f reaction) final-render-fn))
              (._run reaction false))
            (setup-reaction! this final-render-fn))))

      ::fulcro.algo/drop-component!
      (fn drop-component-middleware
        ([this]
         ;(log/info "Drop component!" (c/component-name this))
         (cleanup! this)
         (fulcro.index/drop-component! this))
        ([this ident]
         ;(log/info "Drop component!" (c/component-name this))
         (cleanup! this)
         (fulcro.index/drop-component! this ident))))))
