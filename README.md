This is an extraction of re-frame subscriptions into its own library, where the db is always passed explicitly instead
of accessed via a global Var.

The original intent was to use subscriptions with fulcro, but can easily be used with any single-storage app db data 
design.
