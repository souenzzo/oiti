> **warning**
> Under development. Unstable API. Wrong implementations. Not released yet.

# oiti

> Connecting [ring](https://github.com/ring-clojure/ring) with [OpenAPI](https://swagger.io/specification/)

Oiti is a [lacinia](https://github.com/walmartlabs/lacinia)-inspired library to
implement [ring](https://github.com/ring-clojure/ring)-like http servers in
clojure, using [OpenAPI](https://swagger.io/specification/) specs.

Differently from all others ring-openapi clojure implementations, the routes are described directly in OpenAPI format.

# Feature roadmap

- [x] Simple route matcher
- [ ] Expose all parameters
- [ ] Valid the parameters
- [ ] Coerce the parameter values
- [ ] Pluggable body serializes
- [ ] Pluggable body parsers
- [ ] Return cool errors case invalid parameters
- [ ] Developer-friendly error messages
- [ ] Something better than linear route search

# Usage


```clojure
#_(require '[br.dev.zz.oiti :as oiti])
(def openapi
  "The OpenAPI description. It can be parsed from a YAML or JSON file with `oiti/load`"
  {"openapi" "3.0.0",
   "info"    {"title" "hello", "version" "1.0.0"},
   "paths"   {"/hello/{who}" 
              {"get" {"operationId" "hello-op"
                      "responses"   {"default"
                                     {"description" "ok"
                                      "content"     {"application/json" 
                                                     {"schema" {"type"       "object"
                                                                "properties" {"hello" {"type" "string"}}}}}}}}}}})
(def app-handler
  (-> {::oiti/document openapi
       ::oiti/handlers {"hello-op" (fn [{::oiti/keys [path-params]}]
                                    ;; as the path defines `who` parameter, it will be present in here.
                                    ;; all other ring keys, like :uri, :request-method, will be availble too.
                                    {:content {:hello (:who path-params)}
                                     :status  200})}}
    oiti/->ring-handler))

(defn -main
  []
  (run-jetty handler {:port 8080}))
```

# The name

It is a [tree and a fruit](https://pt.wikipedia.org/wiki/Oiti)
