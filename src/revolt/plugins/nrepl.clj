(ns ^{:clojure.tools.namespace.repl/load false} revolt.plugins.nrepl
  (:require [cider.nrepl]
            [io.aviso.ansi]
            [clojure.tools.nrepl.server :as nrepl.server]
            [clojure.tools.logging :as log]
            [refactor-nrepl.middleware :as refactor.nrepl]
            [revolt.plugin :refer [Plugin create-plugin]]))

(defn init-plugin
  "Initializes nREPL plugin."

  [config]
  (reify Plugin
    (activate [this ctx]
      (log/debug "Starting nREPL server")

      (let [handler (apply nrepl.server/default-handler
                           (conj (map #'cider.nrepl/resolve-or-fail cider.nrepl/cider-middleware)
                                 #'refactor.nrepl/wrap-refactor))
            server (nrepl.server/start-server
                    :port (:port config)
                    :handler handler)]

        (spit ".nrepl-port" (:port server))
        (println (io.aviso.ansi/yellow (str "nREPL client can be connected to port " (:port server))))
        server))

    (deactivate [this ret]
      (when ret
        (log/debug "closing nrepl")
        (nrepl.server/stop-server ret)))))
