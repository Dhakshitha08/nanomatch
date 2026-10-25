# Conclusion

## What the project set out to show

The abstract promised "a measured demonstration of why lock-free design matters in high-frequency systems, not just a
working engine". The engine works: it follows price-time priority, both versions produce identical trades on identical
input (including under concurrent load), and the lock-free hot path allocates nothing per order. The measurements give a
more precise answer than a slogan would.

## What the measurements support

- **Under contention, the lock-free design makes the slow outliers much less slow.** With 2, 4 and 8 producers the
  lock-free engine's p99 latency is clearly lower than the lock-based baseline's, for steady and for burst load, with
  min-max ranges over three runs that do not overlap <!-- C3 --><!-- C4 -->. This is the property that matters most for a
  matching engine, where one slow order delays every order behind it.
- **With one producer there is little difference.** The two engines' p99 medians are within 25 % of each other
  <!-- C7 -->. The lock-free advantage appears when threads compete.

## What the measurements do not support

- **It is not faster at everything.** The lock-free engine sustained more orders per second than the baseline with 1 and 2
  producers <!-- C1 -->, but fewer with 4 and 8 <!-- C2 -->, and its own throughput with 1 producer was higher than with 4 or
  8 <!-- C8 -->.
- **It has a higher typical latency under contention.** With 4 and 8 producers the baseline's median latency was lower
  than the lock-free engine's, for steady and for burst load <!-- C5 --><!-- C6 -->.
- **The reasons are only partly established.** The design chapter marks which explanations were tested and which are
  hypotheses; none of the hypotheses about the baseline's tail (lock waiting, garbage collection) or about the median
  (lock fairness) was isolated by an experiment of its own.

So the supported conclusion is narrower than "lock-free is faster": lock-free design buys **predictable tail latency
under contention**, and in this implementation it costs throughput and median latency once several threads submit at the
same time.

## Limits of the evidence

One machine, one operating system, one JVM, three runs, and a workload whose order book stays tiny (the benchmark
measures the cost of handing orders to the matcher and matching them, not a deep book). The lock-free engine's book is
bounded and the baseline's is not, and the baseline uses a standard non-fair lock. The results chapter lists these and
the other limitations.

## Future work

- Find out why throughput falls with more producers (the ring-buffer-only evidence points to where to look) and try
  the usual remedies: claiming several slots at once, padding the slots themselves, or sharding producers across
  several rings feeding one matcher.
- Run the baseline with a fair lock to test the fairness hypothesis for the median and the tail.
- Benchmark with a deep book and with cancels, which needs a different workload and an order book that supports removal.
- Replace the linear search for the next best price with a bitmap, so the price range can be wide.
- Everything in the last section of the design chapter: persistence, a network protocol, many instruments, richer order
  types, audit logging, and platform tuning.
