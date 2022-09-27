(ns space.matterandvoid.subscriptions.impl.eql-protocols)

(defprotocol IDataSource
  (-attribute-subscription-fn [this id-attr attribute]
    "Returns a function that returns a Reactive (RCursor or Reaction) type for extracting a single attribute of an entity.")
  (-ref->attribute [this ref] "Given a ref type or a full entity return the attribute used for the entity's ref. ex: :user/id for the ref [:user/id 1]")
  (-ref->id [this ref] "Given a ref type for storing normalized relationships, or a full entity, return the ID of the pointed to entity.")
  (-entity-id [this db id-attr args-query-map])
  (-entity [this db id-attr args-query-map])
  (-attr [this db id-attr attr args-query-map]))
