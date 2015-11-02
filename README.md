This script generates a table of streaming Film Sack movies in Reddit's Markdown format. Data is gathered from CanIStreamIt.com and the podcast's RSS feed.

## Setup

Create an SQLite database called `movies.db` from the supplied `movies.sql`
file. This database contains the IMDB, Rotten Tomatoes and CISI ids of each episode's
film.

## Usage

    lein exec streamable.clj

## License

MIT

