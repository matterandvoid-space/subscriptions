(ns space.matterandvoid.subscriptions.fulcro-eql
  (:require
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [edn-query-language.core :as eql]
    [space.matterandvoid.subscriptions.fulcro :as fulcro.subs :refer [<sub reg-sub-raw sub-fn]]
    [space.matterandvoid.subscriptions.impl.eql-protocols :as proto]
    [taoensso.timbre :as log]
    [space.matterandvoid.subscriptions.impl.eql-queries :as impl]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom :refer [cursor]]))

(def query-key impl/query-key)
(def missing-val impl/missing-val)
(def walk-fn-key impl/walk-fn-key)
(def xform-fn-key impl/xform-fn-key)

(defn valid-datasource? [?ds]
  (or
    (fulcro.app/fulcro-app? ?ds)
    (ratom/deref? ?ds)
    (map? ?ds)))

(defn ->db
  "Subscriptions support passing: a fulcro app, the fulcro state atom, or the state hashmap itself.
  This function returns the state hashmap for any of these inputs."
  [?fulcro-app]
  (cond
    (fulcro.app/fulcro-app? ?fulcro-app)
    (fulcro.app/current-state ?fulcro-app)

    (ratom/deref? ?fulcro-app)
    (deref ?fulcro-app)

    :else
    ?fulcro-app))

(def fulcro-data-source
  (reify proto/IDataSource
    (-attribute-subscription-fn [_ id-attr attr]
      ;; this version uses reactions instead of cursors
      #_(vary-meta (sub-fn
                     (fn [?fulcro-app args]
                       ;; this is to support passing state map to subscriptions instead of the fulcro app, for example in mutations
                       (cond
                         (fulcro.app/fulcro-app? ?fulcro-app)
                         (ratom/make-reaction (fn [] (get-in (fulcro.app/current-state ?fulcro-app) [id-attr (get args id-attr) attr])))

                         (ratom/deref? ?fulcro-app)
                         (ratom/make-reaction (fn [] (get-in @?fulcro-app [id-attr (get args id-attr) attr])))

                         :else
                         (ratom/make-reaction (fn [] (get-in ?fulcro-app [id-attr (get args id-attr) attr])))))
                     )
          assoc ::fulcro.subs/sub-name attr)
      (fulcro.subs/with-name
        (fn [?fulcro-app args]
          ;; this is to support passing state map to subscriptions instead of the fulcro app, for example in mutations
          (cond
            (fulcro.app/fulcro-app? ?fulcro-app)
            (cursor (::fulcro.app/state-atom ?fulcro-app) [id-attr (get args id-attr) attr])

            (ratom/deref? ?fulcro-app)
            (cursor ?fulcro-app [id-attr (get args id-attr) attr])

            :else
            (ratom/make-reaction (fn [] (get-in ?fulcro-app [id-attr (get args id-attr) attr])))))
        attr))
    (-ref->attribute [_ ref] (first ref))
    (-ref->id [_ ref]
      (cond (eql/ident? ref) (second ref)
            (map? ref) (let [id-key (first (filter (comp #(= % "id") name) (keys ref)))]
                         (get ref id-key))
            :else ref))
    (-entity-id [_ _ id-attr args] (get args id-attr))
    (-entity [_ fulcro-app id-attr args]
      (when (eql/ident? [id-attr (get args id-attr)])
        (get-in (->db fulcro-app) [id-attr (get args id-attr)])))
    (-attr [_ fulcro-app id-attr attr args]
      (get-in (->db fulcro-app) [id-attr (get args id-attr) attr]))))

(defn nc
  "Wraps fulcro.raw.components/nc to take one hashmap of fulcro component options, supports :ident being a keyword.
  Args:
  :query - fulcro eql query
  :ident - keyword or function
  :name -> same as :componentName (fully qualified keyword or symbol)
  Returns a fulcro component (or a mock version if fulcro is not installed) created by fulcro.raw.components/nc"
  [args] (impl/nc args))

(def get-query impl/get-query)
(def class->registry-key impl/class->registry-key)
(def get-ident impl/get-ident)

(def ^:private fulcro-form-state-config-sub
  (fulcro.subs/with-name
    (impl/create-component-subs ::fulcro.subs/sub-name <sub sub-fn fulcro-data-source fs/FormConfig {})
    (keyword `fulcro-form-state-config-sub)))

(defn ^:private query-contains-form-config? [component]
  (-> (rc/get-query component)
    (eql/focus-subquery [fs/form-config-join])
    not-empty
    boolean))

(defn ->state-map
  [?datasource]
  (cond (fulcro.app/fulcro-app? ?datasource)
        (fulcro.app/current-state ?datasource)
        (ratom/deref? ?datasource) (deref ?datasource)
        :else ?datasource))

(defn expand-ident-sub
  "Takes a layer2 subscription function and an eql subscription for a component.
  Returns a function subscription that invokes the eql subscription for the ident returned from the provided `layer2-sub`."
  [layer2-sub component-eql-sub]
  (let [component (-> component-eql-sub meta ::impl/component)
        component-name (rc/class->registry-key component)
        sub-cache-name (keyword (namespace component-name) (str (name component-name) "-expand-ident"))
        sub (fn expand-ident [fulcro-app args]
              (ratom/make-reaction
                (fn []
                  (let [ident (layer2-sub fulcro-app args)
                        component-query (rc/get-query component)]
                    (when (and ident (second ident))
                      (let [[id-attr id-value] ident]
                        (component-eql-sub fulcro-app {query-key component-query, id-attr id-value})))))))]
    (fulcro.subs/with-name
      (sub-fn sub)
      sub-cache-name)))

(defn expand-ident-list-sub
  "Takes a layer2 subscription function and an eql subscription for a component.
  Returns a function subscription that invokes the eql subscription for each ident in the list of idents returned from the provided `layer2-sub`."
  [layer2-sub component-eql-sub]
  (let [component (-> component-eql-sub meta ::impl/component)
        component-name (rc/class->registry-key component)
        sub-cache-name (keyword (namespace component-name) (str (name component-name) "-expand-ident-list"))
        sub (fn expand-ident-list [fulcro-app args]
              (ratom/make-reaction
                (fn []
                  (let [idents (layer2-sub fulcro-app args)
                        component-query (rc/get-query component)]
                    (filterv some?
                      (map (fn [[id-attr id-value]]
                             (when id-value
                               (component-eql-sub fulcro-app {query-key component-query, id-attr id-value})))
                        idents))))))]
    (fulcro.subs/with-name
      (sub-fn sub)
      sub-cache-name)))

(defn create-eql-facade
  "Allows invoking an eql subscription by passing:
  (sub-fn app-db id-value eql-query)
  or
  (sub-fn app-db id-value eql-query {:extra-args :here `xform-fn (fn[v] ,,,)})

  as well as the verbose syntax:
  (sub-fn app-db {your-id-attr id-value subs/eql-query-key eql-query ,, other-map-args,,})"
  [id-attr eql-sub-fn]
  (with-meta
    (fn eql-facade
      ([fulcro-app opts]
       (assert (valid-datasource? fulcro-app) "Invalid datasource passed to eql subscription")
       (eql-sub-fn fulcro-app opts))
      ([fulcro-app id-value query]
       (assert (valid-datasource? fulcro-app) "Invalid datasource passed to eql subscription")
       (eql-sub-fn fulcro-app {query-key query, id-attr id-value}))
      ([fulcro-app id-value query args-map]
       (assert (valid-datasource? fulcro-app) "Invalid datasource passed to eql subscription")
       (eql-sub-fn fulcro-app (assoc args-map query-key query, id-attr id-value))))
    (meta eql-sub-fn)))

(defn create-component-subs
  "Creates a subscription function that will fulfill the given Fulcro component's query.
  The component and any components in its query must have a name (cannot be anonymous).

  the `sub-joins-map` argument is a hashmap whose keys are the join properties of the component and whose value is a
  subscription function for normal joins, and a nested hashmap for unions of the union key to subscription.

  You do not need to provide a subscription function for recursive joins.

  You do not need to provide a subscription for Fulcro's form-state config join if you component includes that join in its query,
  a subscription will be created for you for form-state/config.

  Call the subscription with the id value in the options map and provide an optional EQL query in the query map using the subs/query-key key."
  ([component] (create-component-subs component {}))
  ([component sub-joins-map]
   (let [[id-prop] (get-ident component {})
         sub-joins-map (cond-> sub-joins-map (query-contains-form-config? component) (assoc ::fs/config fulcro-form-state-config-sub))
         eql-sub-fn (impl/create-component-subs ::fulcro.subs/sub-name <sub sub-fn fulcro-data-source component sub-joins-map)]
     (create-eql-facade id-prop eql-sub-fn))))

(defn register-component-subs!
  "Registers subscriptions that will fulfill the given fulcro component's query.
  The component and any components in its query must have a name (cannot be anonymous)."
  [c] (impl/register-component-subs! reg-sub-raw <sub fulcro-data-source c))
