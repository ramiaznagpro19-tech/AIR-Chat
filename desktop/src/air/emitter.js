// Tiny synchronous event emitter.
export class Emitter {
  #handlers = new Map();

  on(type, fn) {
    if (!this.#handlers.has(type)) this.#handlers.set(type, new Set());
    this.#handlers.get(type).add(fn);
    return () => this.#handlers.get(type)?.delete(fn);
  }

  emit(type, ...args) {
    for (const fn of [...(this.#handlers.get(type) || [])]) {
      try {
        fn(...args);
      } catch (e) {
        console.error("[air] handler failed", type, e);
      }
    }
  }
}
