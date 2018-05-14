(set-env!
 :source-paths   #{"src"}
 :resource-paths #{"resources"}
 :dependencies   '[[org.clojure/clojure "1.9.0"]
                   [org.clojure/java.classpath "0.2.3"]
                   [org.clojure/tools.cli "0.3.5"]
                   [org.clojure/tools.logging "0.4.0"]
                   [adzerk/bootlaces "0.1.13" :scope "test"]
                   [metosin/bat-test "0.4.0" :scope "test"]
                   [ch.qos.logback/logback-classic "1.2.3" :scope "provided"]
                   [net.sf.jpathwatch/jpathwatch "0.95"]
                   [io.aviso/pretty "0.1.34"]])

;; to check the newest versions:
;; boot -d boot-deps ancient

(def +version+ "0.0.1")

(require
 '[clojure.tools.namespace.repl]
 '[adzerk.bootlaces :refer :all]
 '[metosin.boot-alt-test :refer [alt-test]])

(bootlaces! +version+)

;; which source dirs should be monitored for changes when resetting app?
(apply clojure.tools.namespace.repl/set-refresh-dirs (get-env :source-paths))

(task-options!
 pom {:project 'defunkt/revolt
      :version +version+
      :description "Trampoline to beloved clojure tools"
      :url "https://github.com/mbuczko/revolt"
      :scm {:url "https://github.com/mbuczko/revolt"}})
