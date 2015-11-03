This script generates a table of streaming Film Sack movies in Reddit's
Markdown format. Data is gathered from Rotten Tomatoes, canistream.it and the
podcast's RSS feed.

## Setup

Create an SQLite database called `episodes.db` from the supplied `episodes.sql`
file.

    cat episodes.sql | sqlite3 episodes.db

This database contains the IMDB, Rotten Tomatoes and CISI ids of each episode's
film.

## Usage

To generate a table of streaming movies, run `streamable.clj`

    lein exec streamable.clj

To update the episode database, run `update_episodes.clj`.

    lein exec update_episodes.clj

For each episode, you will be prompted to select a movie from a list of
results. If the correct movie is not in the list, you can use your own search
term by selecting 'manual search.' For episodes that you do not wish to
include, you can select 'skip' or 'ignore forever.'

## License

MIT

