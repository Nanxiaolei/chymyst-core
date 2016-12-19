# The chemical machine paradigm

`JoinRun`/`Chymyst` adopts an unusual approach to declarative concurrent programming.
This approach is purely functional but does not use threads, futures, or monads.
It is easiest to understand this approach by using the **chemical machine** metaphor.

## Simulation of chemical reactions

Imagine that we have a large tank of water where many different chemical substances are dissolved.
Different chemical reactions are possible in this “chemical soup”, as various molecules come together and react, producing other molecules.
Reactions could start at the same time (i.e. concurrently) in different regions of the soup.

Chemical reactions are written like this:

HCl + NaOH ⇒ NaCl + H<sub>2</sub>O

A molecule of hydrochloric acid (HCl) reacts with a molecule of sodium hydroxide (NaOH) and yields a molecule of salt (NaCl) and a molecule of water (H<sub>2</sub>O).

Since we are going to simulate reactions in a computer, we make the “chemistry” completely arbitrary.
We can define molecules of any sort, and we can postulate arbitrary reactions between them.

For instance, we can postulate that there exist three sorts of molecules called `a`, `b`, `c`, and that they can react as follows:

`a + b ⇒ a`

`a + c ⇒` [_nothing_]


![Reaction diagram a + b => a, a + c => ...](http://winitzki.github.io/joinrun-scala/reactions1.svg)

Of course, real-life chemistry does not allow a molecule to disappear without producing any other molecules.
But our chemistry is purely imaginary, and so the programmer is free to postulate arbitrary chemical laws.

To develop the chemical analogy further, we allow the chemical soup to hold many copies of each molecule.
For example, the soup can contain five hundred copies of `a` and three hundred copies of `b`, and so on.
We also assume that we can inject any molecule into the soup at any time.

It is not difficult to implement a simulator for the chemical behavior we just described.
Having specified the list of chemical laws and injected some initial molecules into the soup, we start the simulation.
The chemical machine will run all the reactions that are allowed by the chemical laws.

We will say that in a reaction such as

`a + b + c ⇒ d + e`

the **input molecules** are  `a`, `b`, and `c`, and the **output molecules** are `d` and `e`.
A reaction can have one or more input molecules, and zero or more output molecules.

Once a reaction starts, the input molecules instantaneously disappear from the soup (they are “consumed” by the reaction), and then the output molecules are injected into the soup.

The simulator will start many reactions concurrently whenever their input molecules are available.

## Concurrent computations on the chemical machine

The “chemical machine” is implemented by the runtime engine of `JoinRun`.
Now, rather than merely watch as reactions happen, we are going to use this engine for running actual concurrent programs.

To this end, we are going to modify the “chemical machine” as follows:

1. Each molecule in the soup is required to _carry a value_. Molecule values are strongly typed: A molecule of a given sort (such as `a` or `b`) can only carry values of some fixed type (such as `Boolean` or `String`).

2. Since molecules must carry values, we now need to specify a value of the correct type when we inject a new molecule into the soup.

3. For the same reason, reactions that produce new molecules will now need to put values on each of the output molecules. These output values must be _functions of the input values_, -- that is, of the values carried by the input molecules consumed by this reaction. Therefore, each reaction will now need to carry a Scala expression (called the **reaction body**) that will compute the new output values and inject the output molecules.

In this way, the chemical machine can be programmed to run arbitrary computations.

We will use the syntax such as `b(123)` to denote molecule values.
In a chemical law, the syntax `b(123)` means that the molecule `b` carries an integer value `123`.
Molecules to the left-hand side of the arrow are input molecules of the reaction; molecules on the right-hand side are output molecules.

A typical reaction (equipped with molecule values and a reaction body) looks like this in pseudocode syntax:

```scala
a(x) + b(y) ⇒ a(z)
where z = computeZ(x,y) // -- reaction body

```

In this example, the reaction's input molecules are `a(x)` and `b(y)`; that is, the input molecules have chemical designations `a` and `b` and carry values `x` and `y` respectively.

The reaction body is an expression that receives `x` and `y` from the input molecules.
The reaction computes a value `z` out of `x` and `y` using the function `computeZ` (or any other code as needed).
The newly computed value `z` is placed onto the output molecule `a`, which is injected back into the soup.

Another example of reaction is

```scala
a(x) + c(y) ⇒ println(x+y) // -- reaction body with no output molecules

```

This reaction consumes the molecules `a` and `c` but does not inject any output molecules.
The only result of running the reaction is the side-effect of printing the number `x+y`.

![Reaction diagram a(x) + b(y) => a(z), a(x) + c(y) => ...](http://winitzki.github.io/joinrun-scala/reactions2.svg)

The computations performed by the chemical machine are _automatically concurrent_:
Whenever input molecules are available in the soup, the runtime engine will start a reaction that consumes these input molecules.
If many copies of input molecules are available, the runtime engine could start several reactions concurrently.
(The runtime engine can decide how many reactions to run depending on system load and the number of available cores.)

Note that every reaction receives the values carried by its _input_ molecules.
The reaction body can be a pure function that computes output values solely from the input values.
If the reaction body is a pure function, it is completely safe (free of race conditions) to execute concurrently several copies of the same reaction, since each copy will consume a different set of input molecules.
This is how the chemical machine achieves safe and automatic concurrency in a purely functional way.

## The syntax of `JoinRun`

So far, we have been using a kind of chemistry-resembling pseudocode to illustrate the structure of reactions in `JoinRun`.
The actual syntax of `JoinRun` is only a little more verbose than that pseudocode:

```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

// declare the molecule types
val a = m[Int] // a(...) will be a molecule with an integer value
val b = m[Int] // ditto for b(...)

// declare the available reaction(s)
join(
  run { case a(x) + b(y) =>
    val z = computeZ(x,y)
    a(z)
  }
)

```

The helper functions `m`, `join`, and `run` are defined in the `JoinRun` library.

## Example: Concurrent counter

We already know enough to start implementing a first concurrent program.

Suppose we need to maintain a counter with an integer value, which can be incremented or decremented by non-blocking concurrent requests.
(For example, we would like to be able to increment and decrement the counter from different processes running at the same time.)

To implement this in `JoinRun`, we begin by deciding which molecules we will need to define.
Since there is no global state, it is clear that the integer value of the counter needs to be carried by a molecule.
Let's call this molecule `counter` and specify that it carries an integer value:

```scala
val counter = m[Int]

```

The increment and decrement requests must be represented by other molecules.
Let us call them `incr` and `decr`.
These molecules do not need to carry values, so we will define the `Unit` type as their value type:

```scala
val incr = m[Unit]
val decr = m[Unit]

```

Now we need to define the chemical reactions.
The reactions must be such that the counter's value is incremented when we inject the `incr` molecule, and decremented when we inject the `decr` molecule.

So, it looks like we will need two reactions:

```scala
join(
  run { case counter(n) + incr(_) => counter(n+1) },
  run { case counter(n) + decr(_) => counter(n-1) }
)

```

The new value of the counter (either `n+1` or `n-1`) will be carried by the new counter molecule that we inject in these reactions.
The previous counter molecule (with its old value `n`) will be consumed by the reactions.
The `incr` and `decr` molecules will be likewise consumed.

![Reaction diagram counter(n) + incr => counter(n+1) etc.](http://winitzki.github.io/joinrun-scala/counter-incr-decr.svg)

It is important to note that the two reactions need to be defined together in a single call to `join`.
The reason is that both reactions contend on the same input molecule `counter`.

This construction -- defining several reactions together -- is called a **join definition** and is written using the library function `join`.
In `JoinRun`, all reactions that consume a given input molecule must be included in a single join definition.

After defining the molecules and their reactions, we can start injecting new molecules into the soup:

```scala
counter(100)
incr() // now the soup has counter(101)
decr() // now the soup again has counter(100)
decr() + decr() // now the soup has counter(98)

```

The syntax `decr() + decr()` is a shorthand for injecting several molecules at once.

It could happen that we are injecting `incr()` and `decr()` molecules too quickly for reactions to start.
This will result in many instances of `incr()` or `decr()` molecules being present in the soup, waiting to be consumed.
Is this a problem?

Recall that when the chemical machine starts a reaction, all input molecules are consumed first, and then the reaction body is evaluated.
In our case, each reaction needs to consume a `counter` molecule, but only one instance of `counter` molecule is initially present in the soup.
For this reason, the chemical machine will need to choose whether the single `counter` molecule will react with an `incr` or a `decr` molecule.
Only when the incrementing or the decrementing calculation is finished, the new instance of the `counter` molecule will be injected into the soup.
This automatically prevents race conditions with the counter: There is no possibility of updating the counter value simultaneously from different reactions.

## Tracing the output

The code shown above will not print any output, so it is perhaps instructive to put some print statements into the reactions.

```scala
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._

// declare the molecule types
val counter = m[Int]
val incr = m[Unit]
val decr = m[Unit]

// helper function to be used in reactions
def printAndInject(x: Int) = {
  println(s"new value is $x")
  counter(x)
}

// declare the available reaction(s)
join(
  run { case counter(n) + decr(_) => printAndInject(n-1) }
  run { case counter(n) + incr(_) => printAndInject(n+1) },
)

counter(100)
incr() // prints “new value is 101"
decr() // prints “new value is 100"
decr() + decr() // prints “new value is 99” and then “new value is 98"

```

## Debugging

`JoinRun` has some debugging facilities to help the programmer verify that the chemistry works as intended.

### Logging the contents of the soup

For debugging purposes, it is useful to see what molecules are waiting for reactions at a given time.
This is achieved by calling the `logSoup` method on the molecule injector.
This method will return a string showing the molecules that are currently present in the soup and are waiting to react with other molecules.
The `logSoup` output will also show the values carried by each molecule.

After executing the code from the example above, here is how we could use this debugging facility:

```
> println(counter.logSoup)
Join{counter + decr => ...; counter + incr => ...}
Molecules: counter(98)

```

The debug output gives us two pieces of information:

1. The JD which is being logged: `Join{counter + decr => ...; counter + incr => ...}`
Note that the JD is identified by the reactions that are defined in it. The reactions are shown in a shorthand notation, by listing only the input molecules.

2. The molecules that are currently waiting in the soup belonging to that JD, namely `Molecules: counter(98)`.
In this example, there is presently only one copy of the `counter` molecule, carrying the value `98`.

Note that the debug output is limited to the molecules that can be consumed by reactions in that JD.
We call them molecules **bound** to that JD.
The JD will look at the presence or absence of these molecules when it decides which reactions to start.

### Molecule names

A perceptive reader will ask at this point:
How did the program know the names `counter`, `decr`, and `incr`?
These are names of local variables we defined using `val counter = m[Int]` and so on.
Ordinarily, Scala code does not have access to these names.

The magic is actually performed by the method `m`, which is a macro that looks up the name of the enclosing variable.
The same effect can be achieved without macros at the cost of more boilerplate:

```scala
val counter = new M[Int]("counter")
// completely equivalent to `val counter = m[Int]`

```

Descriptive names of molecules are very useful for visualizing the reactions, as well as for debugging and logging.
In this tutorial, we will always use macros to define molecules.

### Logging the flow of reactions and molecules

To get asynchronous, real-time logging information about the molecules being consumed or injected and about the reactions being started, the user can set the logging level on the JD.
This is done by calling `setLogLevel` on any molecule injector that is bound to that JD.

```scala
counter.setLogLevel(2)

```

After this, verbosity level 2 is set on all reactions involving the JD to which `counter` is bound.
This might result in a large printout if many reactions are proceeding.
So this facility should be used only for debugging or testing.

## Common errors

### Error: Injecting molecules without defined reactions

For each molecule, there must exist a single join definition (JD) to which this molecule is **bound** -- that is, the JD where this molecule is consumed as input molecule by some reactions.
(See [Join Definitions](joinrun.md#join-definitions) for more details.)

It is an error to inject a molecule that is not yet defined as input molecule in any JD (i.e. not yet bound to any JD).

```scala
val x = m[Int]
x(100) // java.lang.Exception: Molecule x is not bound to any join definition

```

The same error will occur if such injection is attempted inside a reaction body, or if we call `logSoup` on the molecule injector.

The correct way of using `JoinRun` is first to define molecules, then to create a JD where these molecules are used as inputs for reactions, and only then to start injecting these molecules.

The method `isBound` can be used to determine at run time whether a molecule has been already bound to a join definition:

```scala
val x = m[Int]
x.isBound // returns `false`

join( run { case x(2) =>  } )

x.isBound // returns `true`

```

### Error: Redefining input molecules

It is also an error to write a reaction whose input molecule was already used as input in another JD.

```scala
val x = m[Int]
val a = m[Unit]
val b = m[Unit]

join( run { case x(n) + a(_) => println(s"have x($n) + a") } ) // OK, "x" is now bound to this JD.

join( run { case x(n) + b(_) => println(s"have x($n) + b") } )
// java.lang.Exception: Molecule x cannot be used as input since it is already bound to Join{a + x => ...}

```

Correct use of `JoinRun` requires that we put these two reactions together into _one_ join definition:
 
```scala
val x = m[Int]
val a = m[Unit]
val b = m[Unit]

join(
  run { case x(n) + a(_) => println(s"have x($n) + a") },
  run { case x(n) + b(_) => println(s"have x($n) + b") }
) // OK

``` 

More generally, all reactions that share any input molecules must be defined together in a single JD.
However, reactions that use a certain molecule only as an output molecule can be (and should be) written in another JD.
Here is an example where we define one JD that computes a result and sends it on a molecule called `show`, which is bound to another JD:

```scala
val show = m[Int]
// JD where the “show” molecule is an input molecule
join( run { case show(x) => println(s"") })

val start = m[Unit]
// JD where the “show” molecule is an output molecule (but not an input molecule)
join(
  run { case start(_) => val res = compute(...); show(res) }
)

``` 

### Error: Nonlinear pattern

`JoinRun` also requires that all input molecules for a reaction should be of different chemical sorts.
It is not allowed to have a reaction with repeated input molecules, e.g. of the form `a + a => ...` where the molecule of sort `a` is repeated.
An input molecule pattern with a repeated molecule is called a “nonlinear pattern”.

```scala
val x = m[Int]
join(run { case x(n1) + x(n2) =>  })
// java.lang.Exception: Nonlinear pattern: x used twice

``` 

Sometimes it appears that repeating input molecules is the most natural way of expressing the desired behavior of certain concurrent programs.
However, I believe it is always possible to introduce some new auxiliary molecules and to rewrite the “chemistry laws” so that input molecules are not repeated while the resulting computations give the same results.
This limitation could be lifted in a later version of `JoinRun` if it proves useful to do so.

## Order of reactions and nondeterminism

When there are several different reactions that can start the available molecules, the runtime engine will choose the reaction at random,
so that every reaction has an equal chance of starting.

Similarly, when there are several copies of the same molecule that can be consumed as input by a reaction, the runtime engine will make a choice of which copy 
of the molecule to consume.
Currently, `JoinRun` will _not_ fully randomize the input molecules but make an implementation-dependent choice.
A truly random selection of input molecules may be implemented in the future.

Importantly, it is _not possible_ to assign priorities to reactions or to molecules.
The order of reactions in a join definition is ignored, and the order of molecules in the input list is also ignored.
Just for the purposes of debugging, molecules will be printed in alphabetical order of names, and reactions will be printed in an unspecified order.

The result is that the order in which reactions will start is non-deterministic and unknown.

If the priority of certain reactions is important for a particular application, it is the programmer's task to design the chemical laws in such a way that those reactions start in the desired order.
This is always possible by using auxiliary molecules and/or guard conditions.

In fact, a facility for assigning priority to molecules or reactions would be self-defeating.
It will only give the programmer _an illusion of control_ over the order of reactions, while actually introducing subtle nondeterministic behavior.

To illustrate this on an example, suppose we would like to compute the sum of a bunch of numbers in a concurrent way.
We expect to receive many molecules `data(x)` with integer values `x`,
and we need to compute and print the final sum value when no more `data(x)` molecules are present.

Here is an (incorrect) attempt to write chemical laws for this program:

```scala
val data = m[Int]
val sum = m[Int]
join (
  run { case data(x) + sum(y) => sum(x+y) }, // We really want the first reaction to be high priority
   
  run { case sum(x) => println(s"sum = $x") }  // and run the second one only after all `data` molecules are gone.
)
data(5) + data(10) + data(150)
sum(0) // expect "sum = 165"

```

Our intention was to run only the first reaction and to ignore the second reaction as long as `data` molecules are available in the soup.
The chemical machine does not actually allow us to assign a higher priority to the first reaction.
But, if we were able to do that, what would be the result?

In a real-life situation, the `data` molecules are going to be injected concurrently by different processes.
(There wouldn't be much point in making the `data` molecules concurrent if they were all guaranteed to be present at the start of our program: we would have just used an array instead.)

Since these other processes are concurrent and inject `data` molecules at unpredictable times,
it could happen that the `data` molecules are injected somewhat more slowly than we are consuming them.
If that happens, there will be a brief interval of time when no `data` molecules are in the soup (although other processes are about to inject some more of them).
The chemical machine will then run the second reaction, consume the `sum` molecule and print the result, signalling (incorrectly) that the computation is finished.
Perhaps this failure will _rarely_ happen, -- it unlikely to show up in your unit tests, but at some point it is definitely going to happen in production.

This kind of nondeterminism is the prime reason concurrency is widely regarded as a hard programming problem.

`JoinRun` will actually reject our attempted program and print an error message before running anything, immediately after we define the chemical laws with `join`:

```scala
val data = m[Int]
val sum = m[Int]
join (
  run { case data(x) + sum(y) => sum(x+y) },
  run { case sum(x) => println(s"sum = $x") }
)
```

```
Exception: In Join{data + sum => ...; sum => ...}: Unavoidable nondeterminism: reaction data + sum => ... is shadowed by sum => ...

```

The error message means that the reaction `sum => ...` will sometimes prevent `data + sum => ...` from running,
and the programmer will have no control over this nondeterminism.

The correct way of implementing this problem is to keep track of how many `data` molecules we already consumed,
and to emit `done` when we reach the total expected number of the `data` molecules.
Since reactions do not have mutable state, the information about the remaining `data` molecules has to be carried on the `sum` molecule.
So, we will define the `sum` molecule with type `(Int,Int)`, where the second integer will be the number of `data` molecules that remain to be consumed.

The reaction `data + sum` should proceed only when we know that some `data` molecules are still remaining.
Otherwise, `sum` should start its own reaction and print the final result. 

```scala
val data = m[Int]
val sum = m[(Int, Int)]
join (
  run { case data(x) + sum((y, remaining)) if remaining > 0 => sum((x+y, remaining - 1)) },
  run { case sum((x, 0)) => println(s"sum = $x") }
)
data(5) + data(10) + data(150) // inject three `data` molecules
sum((0, 3)) // expect "sum = 165" printed

```

Now are chemical laws are fully deterministic, and no priority needs to be explicitly assigned.

The chemical machine forces the programmer to design the chemistry in such a way that
the order of running reactions is completely determined by the data on the available molecules.

Another way of maintaining determinism is to remove the reactions that may shadow each other.
Here is an equivalent solution with just one reaction:

```scala
val data = m[Int]
val sum = m[(Int, Int)]
join (
  run { case data(x) + sum((y, remaining)) =>
      val newSum = x + y
      if (remaining == 1)  println(s"sum = $newSum")
      else  sum((newSum, remaining-1)) 
     }
)
data(5) + data(10) + data(150) // inject three `data` molecules
sum((0, 3)) // expect "sum = 165" printed

```


## Summary so far

The chemical machine requires for its description:

- a list of defined molecules, together with their types;
- a list of reactions involving these molecules as inputs, together with reaction bodies.

These definitions comprise the chemical laws of a concurrent program.

The user can define reactions in one or more join definitions.
Each join definition encompasses all reactions that have some _input_ molecules in common.
Different join definitions must have no input molecules in common.

In this way, a complicated system of interacting concurrent processes can be specified through a particular set of chemical laws and reaction bodies.

After defining the molecules and specifying the reactions, the user can start the program by injecting some initial molecules into the soup.

Let us recapitulate the core ideas of the chemical paradigm of concurrency:

In the chemical machine, there is no mutable global state; all data is immutable and must be carried by some molecules.
Each of these molecules has a specific chemical designation, such as `a`, `b`, `counter`, and so on.
These chemical designations are not strings `"a"` or `"b"`; one could imagine writing

```scala
val a = m[Int]
val q = a

```

This will copy the molecule injector `a` into another local value `q`.
However, this does not change the chemical designation of a molecule.
The injector `q` will inject the same molecules as `a`.

The chemical designation of the molecule specifies two aspects of the concurrent program:

- which other input molecules (besides this one) are required to start a computation;
- which computation will be performed when all the required input molecules are available.

Each reaction specifies its input molecules, and in this way determines all the data necessary for computing the reaction body.
The chemical machine will automatically make this data available, since a reaction can start only when all its input molecules are present in the soup.

Each reaction also specifies a reaction body, which is a Scala expression that evaluates to `Unit`.
This expression can perform arbitrary computations using the input molecule values.
It can also inject new molecules into the soup, which is a side effect of calling a molecule injector function.

Up to this side effect, the reaction body can be a pure function, if it only depends on the input data of the reaction.
In this case, many copies of the reaction can be safely executed concurrently if many sets of input molecules are available.
Also, the reaction can be safely and automatically restarted in the case of a transient failure.

The chemical laws fully specify which computations need to be performed for the data on the given molecules.
Whenever multiple sets of data are available, computations will be performed concurrently.

# Example: Declarative solution for “dining philosophers"

The ["dining philosophers problem"](https://en.wikipedia.org/wiki/Dining_philosophers_problem) is to run a simulation of five philosophers who take turns eating and thinking.
Each philosopher needs two forks to start eating, and every pair of neighbor philosophers shares a fork.

![Five dining philosophers](An_illustration_of_the_dining_philosophers_problem.png)

The simplest solution of the “dining philosophers” problem is achieved using a molecule for each fork and two molecules per philosopher: one representing a thinking philosopher and the other representing a hungry philosopher.

A “thinking philosopher” molecule (`t1`, `t2`, ..., `t5`) causes a reaction in which the process is paused for a random time and then the “hungry philosopher” molecule is injected.
A “hungry philosopher” molecule (`h1`, ..., `h5`) needs to react with _two_ neighbor “fork” molecules. The reaction process is paused for a random time, and then the “thinking philosopher” molecule is injected together with the two “fork” molecules.

The complete code is shown here:

```scala
 /**
 * Random wait. Also, print the name of the molecule.
 */
def rw(m: Molecule): Unit = {
  println(m.toString)
  Thread.sleep(scala.util.Random.nextInt(20))
}

val h1 = new M[Int]("Aristotle is eating")
val h2 = new M[Int]("Kant is eating")
val h3 = new M[Int]("Marx is eating")
val h4 = new M[Int]("Russell is eating")
val h5 = new M[Int]("Spinoza is eating")
val t1 = new M[Int]("Aristotle is thinking")
val t2 = new M[Int]("Kant is thinking")
val t3 = new M[Int]("Marx is thinking")
val t4 = new M[Int]("Russell is thinking")
val t5 = new M[Int]("Spinoza is thinking")
val f12 = new M[Unit]("Fork between 1 and 2")
val f23 = new M[Unit]("Fork between 2 and 3")
val f34 = new M[Unit]("Fork between 3 and 4")
val f45 = new M[Unit]("Fork between 4 and 5")
val f51 = new M[Unit]("Fork between 5 and 1")

join (
  run { case t1(_) => rw(h1); h1() },
  run { case t2(_) => rw(h2); h2() },
  run { case t3(_) => rw(h3); h3() },
  run { case t4(_) => rw(h4); h4() },
  run { case t5(_) => rw(h5); h5() },

  run { case h1(_) + f12(_) + f51(_) => rw(t1); t1(n) + f12() + f51() },
  run { case h2(_) + f23(_) + f12(_) => rw(t2); t2(n) + f23() + f12() },
  run { case h3(_) + f34(_) + f23(_) => rw(t3); t3(n) + f34() + f23() },
  run { case h4(_) + f45(_) + f34(_) => rw(t4); t4(n) + f45() + f34() },
  run { case h5(_) + f51(_) + f45(_) => rw(t5); t5(n) + f51() + f45() }
)
// inject molecules representing the initial state:
t1() + t2() + t3() + t4() + t5()
f12() + f23() + f34() + f45() + f51()
// Now reactions will start and print to the console.

```

Note that an `h + f + f` reaction will consume a “hungry philosopher” molecule and two “fork” molecules, so these three molecules will not be present in the soup during the time interval taken by the `h + f + f` reaction.
Thus, neighbor philosophers will not be able to start eating until the two “fork” molecules are returned to the soup by that reaction.
The decision of which philosophers start eating will be made randomly, and there will never be a deadlock.

The result of running this program is the output such as

```
Russell is thinking
Aristotle is thinking
Spinoza is thinking
Marx is thinking
Kant is thinking
Russell is eating
Aristotle is eating
Russell is thinking
Marx is eating
Aristotle is thinking
Spinoza is eating
Marx is thinking
Kant is eating
Spinoza is thinking
Russell is eating
Kant is thinking
Aristotle is eating
Aristotle is thinking
Russell is thinking
Spinoza is eating

```

It is interesting to note that this example code is fully declarative: it describes what the “dining philosophers” simulation must do, and the code is quite close to the English-language description of the problem.
