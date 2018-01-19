(ns com.walmartlabs.system-viz
  "Visualize a component system using Graphviz."
  (:require
    [com.stuartsierra.component :as component]
    [clojure.set :as set]
    [clojure.java.browse :refer [browse-url]]
    [clojure.java.io :as io])
  (:import
    (java.io File)
    (java.util.concurrent TimeUnit)))

(defn ^:private quoted [s] (str \" s \"))

(defmacro cond-let
  "A version of `cond` that allows for `:let` terms."
  [& forms]
  {:pre [(even? (count forms))]}
  (when forms
    (let [[test-exp result-exp & more-forms] forms]
      (if (= :let test-exp)
        `(let ~result-exp
           (cond-let ~@more-forms))
        `(if ~test-exp
           ~result-exp
           (cond-let ~@more-forms))))))

(defn ^:private system->dot
  [system horizontal]
  (println "digraph System {")

  (when horizontal
    (println "graph [rankdir=LR]"))

  ;; Now find all the unknown keys (dependencies to unknown components).
  (let [all-keys (->> system
                      vals
                      (mapcat (comp vals component/dependencies))
                      set)
        bad-keys (set/difference all-keys (-> system keys set))]
    (when (seq bad-keys)
      (println "  subgraph {")
      (println "    node [color=red, style=filled, fontcolor=white];")
      (doseq [k bad-keys]
        (println (str "    " (quoted k) ";")))
      (println "  }")))

  (doseq [[component-key component] system
          :let [component-key' (quoted component-key)]]
    (println (format "  %s;" component-key'))
    (doseq [[local-key system-key] (component/dependencies component)]
      (print (format "  %s -> %s"
                     component-key'
                     (quoted system-key)))

      (when-not (= local-key system-key)
        (print (format " [label=%s]"
                       (quoted local-key))))
      (println ";")))

  (println "}"))

(def ^:private wait 5)

(defn visualize-system
  "Visualizes the system as a graph, using Graphviz.
  A temporary file is created to store the Graphviz
  program; this is then fed through the `dot`
  command line tool to generate a PDF file.

  The resulting PDF file may optionally be opened, or
  its location output to the console.

  Generally, this should be invoked before the call to
  `system-start`.

  Options:

  :format
  : The output format as a keyword; defaults to :pdf, but
    :png or :svg are also good choices.

  :open
  : if true (the default), then the generated image file
    will be opened.  If false, then the path to the image file
    will be printed to \\*out\\*.
    
  :horizontal
  : If true (the default is false), then the image will be laid out
    horizontally instead of vertically.
    
  :save-as
  : If provided, then this is the path to which the graphviz source file 
    will be saved.  If not provided, the graphviz source file is generated
    as a temporary file.
      
  Returns the system unchanged."
  ([system]
   (visualize-system system nil))
  ([system options]
   (cond-let
     :let [{:keys [format open save-as horizontal]
            :or {format :pdf
                 horizontal false
                 open true}} options
           format-name (name format)
           dot (with-out-str
                 (system->dot system horizontal))
           gvfile (if (some? save-as)
                    (io/file save-as)
                    (File/createTempFile "system-" ".gv"))
           imagefile (File/createTempFile "system-" (str "." format-name))
           process (do
                     (spit gvfile dot)
                     (.exec (Runtime/getRuntime) (str "dot -T" format-name " " gvfile " -o " imagefile)))]
     (do
       (.waitFor process wait (TimeUnit/SECONDS))
       (.isAlive process))
     (binding [*out* *err*]
       (println "DOT process (to render system map) did not finish after" wait "seconds.")
       (.destroyForcibly process))

     :let [exit-value (.exitValue process)]

     (not (zero? exit-value))
     (binding [*out* *err*]
       (println "DOT process failed with status" (.exitValue process))
       (println (slurp (.getErrorStream process))))

     :let [image-url (.toURL imagefile)]

     open
     (browse-url image-url)

     :else
     (println "System graph rendered to:" (str image-url)))

    ;; Ignore the result of cond-let, and just return the system.
   system))
