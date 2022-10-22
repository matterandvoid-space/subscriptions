(ns space.matterandvoid.subscriptions.impl.reagent-ratom
  (:require-macros [space.matterandvoid.subscriptions.impl.reagent-ratom])
  (:refer-clojure :exclude [atom])
  (:require [reagent.ratom]))

;; Make sure the Google Closure compiler sees this as a boolean constant,
;; otherwise Dead Code Elimination won't happen in `:advanced` builds.
;; Type hints have been liberally sprinkled.
;; https://developers.google.com/closure/compiler/docs/js-for-compiler
(def ^boolean debug-enabled? "@define {boolean}" ^boolean goog/DEBUG)

(defn ratom? [x]
  ;; ^:js suppresses externs inference warnings by forcing the compiler to
  ;; generate proper externs. Although not strictly required as
  ;; reagent.ratom/IReactiveAtom is not JS interop it appears to be harmless.
  ;; See https://shadow-cljs.github.io/docs/UsersGuide.html#infer-externs
  (satisfies? reagent.ratom/IReactiveAtom ^js x))

(def atom reagent.ratom/atom)
(defn deref? [x] (satisfies? IDeref x))
(defn make-reaction [f] (reagent.ratom/make-reaction f))
(defn run-in-reaction
  "Evaluates `f` and returns the result.  If `f` calls `deref` on any ratoms,
   creates a new Reaction that watches those atoms and calls `run` whenever
   any of those watched ratoms change.  Also, the new reaction is added to
   list of 'watches' of each of the ratoms. The `run` parameter is a function
   that should expect one argument.  It is passed `obj` when run.  The `opts`
   are any options accepted by a Reaction and will be set on the newly created
   Reaction. Sets the newly created Reaction to the `key` on `obj`."
  [f obj key run opts] (reagent.ratom/run-in-reaction f obj key run opts))
(defn add-on-dispose! [a-ratom f] (reagent.ratom/add-on-dispose! a-ratom f))
(defn reaction? [r] (instance? reagent.ratom/Reaction r))
(defn cursor? [r] (instance? reagent.ratom/RCursor r))
(defn dispose! [^clj a-ratom]
  (if (cursor? a-ratom)
    (if (.-on-dispose a-ratom)
      (.on-dispose a-ratom)
      (when (.-reaction a-ratom)
        (reagent.ratom/dispose! (.-reaction a-ratom))))
    (reagent.ratom/dispose! a-ratom)))
(defn ^boolean reactive-context? [] (reagent.ratom/reactive?))

(defn reagent-id
  "Produces an id for reactive Reagent values
  e.g. reactions, ratoms, cursors."
  [reactive-val]
  ;; ^:js suppresses externs inference warnings by forcing the compiler to
  ;; generate proper externs. Although not strictly required as
  ;; reagent.ratom/IReactiveAtom is not JS interop it appears to be harmless.
  ;; See https://shadow-cljs.github.io/docs/UsersGuide.html#infer-externs
  (when (implements? reagent.ratom/IReactiveAtom ^js reactive-val)
    (str (condp instance? reactive-val
           reagent.ratom/RAtom "ra"
           reagent.ratom/Reaction "rx"
           reagent.ratom/Track "tr"
           "other")
      (hash reactive-val))))

;; copied from reagent to allow setting an on-destroy callback to cleanup the cursor in the subscription cache.
(defn cached-reaction [f ^clj ratom k ^clj obj destroy]
  (let [m        (.-reagReactionCache ratom)
        m        (if (nil? m) {} m)
        reaction (reagent.ratom/make-reaction
                   f :on-dispose (fn [x]
                                   (as-> (.-reagReactionCache ratom) _
                                     (dissoc _ k)
                                     (set! (.-reagReactionCache ratom) _))
                                   (when (some? obj) (set! (.-reaction obj) nil))
                                   (when (some? (.-on-dispose obj)) (.on-dispose obj))))]
    (set! (.-reagReactionCache ratom) (assoc m k reaction))
    (when (some? obj)
      (set! (.-reaction obj) reaction))))

(defn cursor [^clj ratom path]
  (let [cursor (reagent.ratom/->RCursor ratom path nil nil nil)
        f      (fn [] (get-in @ratom path))]
    (cached-reaction f ratom path cursor nil)
    cursor))

