### Handler Middleware Stack

The Smithy middleware stack provides ordered behavior to be invoked on an
underlying handler. The stack is separated into steps that are invoked in a
static order. A step is a collection of middleware that are injected into a
ordered list defined by the user. The user may add, insert, swap, and remove a
step's middleware. When the stack is invoked the step middleware become static,
and their order cannot be modified.

A stack and its step middleware are **not** safe to modify concurrently.

A stack will use the ordered list of middleware to decorate a underlying
handler. A handler could be something like an HTTP Client that round trips an
API operation over HTTP. 

### Terminology

* **Handler** A Handler is some behavior that operates on a given input value,
  returning a response or error. Handlers may be decorated with Middleware
  producing a new Handler. A middleware may invoke underlying handler multiple
  times, and a handler should be stateless.

* **Middleware**: Middleware are invoked recursively as a call stack, calling the
  underlying next handler it wraps. When a Middleware decorates a handler a new
  handler is returned that other middleware can decorate. Each middleware may
  independently react to the input values passed in, and the response or error
  returned by the handler the middleware wraps.

* **Stack**: A Stack is a collection of middleware that wrap a handler. The
  stack can be broken down into discreet steps. Each step may contain zero or
  more middleware specific to that stack's step.

* **Stack Step**: A Stack Step is a predefined set of middleware that are invoked in a
  static order by the Stack. These steps represent fixed points in the
  middleware stack for organizing specific behavior, such as serialize and
  build. A Stack Step is composed of zero or more middleware that are specific
  to that step. A step may define its on set of input/output parameters the
  generic input/output parameters are cast from. A step calls its middleware
  recursively, before calling the next step in the stack returning the result or
  error of the step middleware decorating the underlying handler.

The following represents the predefined steps within a stack. Each step has an
expectation of what has been completed by the step earlier in the stack. 

* **Initialize**: Prepares the input, and sets any default parameters as
  needed, (e.g. idempotency token, and presigned URLs).

* **Serialize**: Serializes the prepared input into a data structure that can
  be consumed by the target transport's message, (e.g. REST-JSON serialization).

* **Build**: Adds additional metadata to the serialized transport message,
  (e.g. HTTP's Content-Length header, or body checksum). Decorations and
  modifications to the message should be copied to all message attempts.

* **Finalize**: Preforms final preparations needed before sending the message.
  The message should already be complete by this stage, and is only alternated
  to meet the expectations of the recipient, (e.g. Retry and AWS SigV4 request
  signing).

* **Deserialize**: Reacts to the handler's response returned by the recipient
  of the request message. Deserializes the response into a structured type or
  error above stacks can react to.

  
### Adding Middleware to a Stack Step

Middleware can be added to a step front or back, or relative, by name, to an
existing middleware in that stack. If a middleware does not have a name a
unique name will be generated at the middleware is added to the step.


```go
stack := middleware.NewStack()
stack.Initialize.Add(paramValidationMiddleware, middleware.After)
stack.Serialize.Add(marshalOperationFoo, middleware.After)
stack.Deserialize.Add(unmarshalOperationFoo, middleware.After)

resp, err := stack.HandleMiddleware(ctx, req.Input, client)
```
