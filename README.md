# Inertia-Puzzle
Java implementation of the Inertia puzzle solver using BFS, Greedy, Divide &amp; Conquer, Dynamic Programming, and Backtracking with animated visualization.
Inertia Puzzle Solver 🎮
Overview

The Inertia Puzzle Solver is a Java-based application that automatically solves the classic Inertia puzzle game using multiple algorithmic strategies.
The objective of the puzzle is to move a ball across a grid and collect all gems while avoiding mines and obstacles.

The solver demonstrates different algorithm design techniques such as BFS, Greedy Algorithms, Divide and Conquer, Dynamic Programming, and Backtracking.

The application also visually shows the puzzle board and animates the solution step-by-step.

Game Rules

The puzzle consists of a grid containing:

Ball – the player-controlled object.

Gems – collectibles that must all be gathered.

Walls / Blocks – obstacles that stop movement.

Mines – dangerous cells that cause failure if touched.

Stop tiles – tiles where the ball stops sliding.

Movement Mechanism

The ball follows inertia-based movement:

When the ball moves in a direction, it continues sliding until it hits an obstacle.

The ball cannot stop in the middle of empty cells.

The goal is to collect all gems using the fewest moves possible.

Features

Random puzzle level generation

Animated solution visualization

Multiple algorithmic solving strategies

Strategy comparison panel

Automatic solution playback

Performance comparison between algorithms

Implemented Solving Strategies
1. Breadth-First Search (BFS)

Explores all possible states level-by-level to guarantee the shortest solution path.

2. Greedy Algorithm

Chooses moves that collect the maximum number of gems or move closer to the nearest gem.

3. Divide and Conquer

Divides the puzzle into smaller regions and solves them independently.

4. Dynamic Programming

Uses bitmask dynamic programming to determine an efficient order of collecting gems.

5. Backtracking

Explores possible move sequences and prunes branches that cannot produce better solutions.

Technologies Used

Java

Java Swing for GUI visualization

Algorithmic strategies for automated solving

Object-Oriented Programming

Project Purpose

This project demonstrates how different algorithmic strategies can be applied to solve a puzzle problem and compares their performance.

It is designed as a learning tool for algorithm design, search strategies, and optimization techniques.
