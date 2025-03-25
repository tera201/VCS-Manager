# VCS-Manager

A modern, Kotlin-based repository mining and management library

## Overview

VCS-Manager is a rewritten and enhanced version of [SWRMiner](https://github.com/tera201/SWRMiner) originally based on  [RepoDriller](https://github.com/mauricioaniche/repodriller).

## Features

- Extracts and analyzes Git repositories, similar to the original RepoDriller.
- Stores analysis results in an SQLite database for easy querying.
- Maintains compatibility with the original RepoDriller API, allowing seamless integration into existing workflows.

## Use Case

This project is used as a **dependency** in a [VCS-Analysis-Toolkit](https://github.com/tera201/VCS-Analysis-Toolkit) plugin project that performs repository analysis. 
The SQLite storage feature ensures persistent and structured storage of analysis results, making it easier to manage large-scale repository insights.


## License

This project is licensed under the **Apache 2.0 License**. See the `LICENSE` file for details.


