> **Warning**
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

Let start with a spec file `hello-spec.yml`. This spec file defines one route, named `hello-op`.

```yaml
openapi: 3.0.0
info: {title: hello, version: 1.0.0}
paths:
  /hello/{who}:
    get:
      operationId: hello-op
      responses:
        default:
          description: ok
          content:
            application/json:
              schema:
                type: object
                properties:
                  hello: {type: string}
```

Then we need to implement the `hello-op`

```clojure
#_(require '[br.dev.zz.oiti :as oiti])

(defn hello-op-handler
  [{::oiti/keys [path-params]}]
  ;; as the path defines `who` parameter, it will be present in here.
  ;; all other ring keys, like :uri, :request-method, will be availble too.
  {:content {:hello (get path-params "who")}
   :status  200})

(def app-handler
  (-> {::oiti/document "hello-spec.yml"
       ::oiti/handlers {"hello-op" hello-op-handler}}
    oiti/->ring-handler))

(defn -main
  []
  ;; use any ring-like server that you like
  (run-jetty handler {:port 8080}))
```

# The name

It is a [tree and a fruit](https://pt.wikipedia.org/wiki/Oiti)
