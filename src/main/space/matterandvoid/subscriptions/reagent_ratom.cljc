(ns space.matterandvoid.subscriptions.reagent-ratom
  (:refer-clojure :exclude [atom])
  (:require [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]))

(defn atom
  "Returns an Atom on Clojure and a Reagent RAtom on ClojureScript."
  [value] (ratom/atom value))

(defn cursor
  "Creates a Reagent RCursor type given a RAtom source (containing a hashmap) and a path into that hashmap."
  [#?(:cljs ^clj ratom :clj ratom) path]
  (ratom/cursor ratom path))

(defn make-reaction
  "Creates a Reagent Reaction using the supplied function."
  [f] (ratom/make-reaction f))

(defn deref? [x] (ratom/deref? x))
