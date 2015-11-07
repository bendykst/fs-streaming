These scripts are used to generate a table of streaming Film Sack movies in
Reddit's Markdown format. Data is gathered from Rotten Tomatoes, canistream.it,
UNOGS.com and the podcast's RSS feed.

## Setup

Install the Clojure project automation tool Leiningen.

Create an SQLite database called `episodes.db` from the supplied `episodes.sql`
file.

    cat episodes.sql | sqlite3 episodes.db

This database contains the IMDB, Rotten Tomatoes, Netflix and CISI ids of each
episode's film.

If you want to use `update_episodes`, get a Rotten Tomatoes API key and add it
to your environment variables as `RT_API_KEY`. If you want to use
`streamable_intl`, get a UNOGS key and add it as `UNOGS_API_KEY`.

## Usage

To generate a table of streaming movies, run `streamable.clj` or
`streamable_intl.clj` using lein-exec. E.g.

    lein exec streamable.clj

To update the episode database, run `update_episodes.clj` the same way. For
each episode, you will be prompted to select a movie from a list of results. If
the correct movie is not in the list, you can use your own search term by
selecting 'manual search.' For episodes that you do not wish to include, you
can select 'skip' or 'ignore forever.'

## License

MIT

