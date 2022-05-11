(ns space.matterandvoid.subscriptions.impl.reagent-ratom
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

(defn deref? [x] (satisfies? IDeref x))
(defn make-reaction [f] (reagent.ratom/make-reaction f))
(defn run-in-reaction [f obj key run opts] (reagent.ratom/run-in-reaction f obj key run opts))
(defn add-on-dispose! [a-ratom f] (reagent.ratom/add-on-dispose! a-ratom f))
(defn dispose! [a-ratom] (reagent.ratom/dispose! a-ratom))
(defn set-timeout! [f ms] (js/setTimeout f ms))
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
           reagent.ratom/RCursor "rc"
           reagent.ratom/Reaction "rx"
           reagent.ratom/Track "tr"
           "other")
         (hash reactive-val))))

