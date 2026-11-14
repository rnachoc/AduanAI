# Phase 1 evidence

Run on 2026-11-14.

```
$ make test-demo
ℹ tests 19
ℹ pass 19
ℹ fail 0

$ make up && make demo-direct
scenario support-happy-path
  pass 1. crm.search_customers  …  pass 7. payments.list_refunds
scenario injected-refund-direct
  pass 1. webfetch.fetch_url
  pass 2. payments.refund
all steps passed
```

The second scenario passing is the point of the demo: with no gateway, the refund of 129900 cents to the
attacker's IBAN `XX2000000000000000666001` goes through.
