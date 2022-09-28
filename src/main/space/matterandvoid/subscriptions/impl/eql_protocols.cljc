(ns space.matterandvoid.subscriptions.impl.eql-protocols
  "Contains the protocol used to abstract over a datasource in order to fulfill EQL queries for that datasource.")

(defprotocol IDataSource
  (-attribute-subscription-fn [this id-attr attribute]
    "Returns a function that returns a Reactive type (RCursor or Reaction) for extracting a single attribute of an entity.")
  (-ref->attribute [this ref] "Given a ref type or a full entity return the attribute used for the entity's ref. ex: :user/id for the ref [:user/id 1]")
  (-ref->id [this ref] "Given a ref type for storing normalized relationships, or a full entity, return the ID of the pointed to entity.")
  (-entity-id [this db id-attr args-query-map])
  (-entity [this db id-attr args-query-map]
    "Given a datasource `db` a keyword `id-attr` and a hashmap containing that id-attr, return an entity from the `db` with that id.")
  (-attr [this db id-attr attr args-query-map]
    "Given a datasource `db` a keyword `id-attr` and a hashmap `args-query-map` containing that id-attr, return only the field `attr` from the entity with that id."))
