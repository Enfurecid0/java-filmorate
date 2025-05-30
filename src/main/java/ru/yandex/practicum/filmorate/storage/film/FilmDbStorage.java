package ru.yandex.practicum.filmorate.storage.film;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.stereotype.Repository;
import ru.yandex.practicum.filmorate.exception.NotFoundException;
import ru.yandex.practicum.filmorate.exception.ValidationException;
import ru.yandex.practicum.filmorate.model.Film;
import ru.yandex.practicum.filmorate.model.Genre;
import ru.yandex.practicum.filmorate.model.RatingMpa;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class FilmDbStorage implements FilmStorage {

    private final JdbcTemplate jdbcTemplate;

    public FilmDbStorage(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Film> getAllFilms() {
        String sqlQuery = "SELECT * "
                + "FROM films "
                + "JOIN rating_mpa ON films.rating_id = rating_mpa.rating_id";

        // Выполняем запрос и собираем фильмы
        return jdbcTemplate.query(sqlQuery, (rs, rowNum) -> {
            int filmId = rs.getInt("film_id");
            String name = rs.getString("film_name");
            String description = rs.getString("description");
            Long duration = rs.getLong("duration");

            LocalDate releaseDate = rs.getTimestamp("release_date") != null
                    ? rs.getTimestamp("release_date").toLocalDateTime().toLocalDate()
                    : null;

            int mpaId = rs.getInt("rating_id");
            String mpaName = rs.getString("rating_name");
            RatingMpa mpa = new RatingMpa(mpaId, mpaName);

            Set<Genre> genres = getGenres(filmId);

            return buildFilm(filmId, name, description, duration, releaseDate, mpa, genres);
        });
    }

    @Override
    public Film createFilm(Film film) {
        Map<String, Object> keys = new SimpleJdbcInsert(this.jdbcTemplate)
                .withTableName("films")
                .usingColumns("film_name", "description", "duration", "release_date", "rating_id")
                .usingGeneratedKeyColumns("film_id")
                .executeAndReturnKeyHolder(Map.of("film_name", film.getName(),
                        "description", film.getDescription(),
                        "duration", film.getDuration(),
                        "release_date", java.sql.Date.valueOf(film.getReleaseDate()),
                        "rating_id", film.getMpa().getId()))
                .getKeys();
        film.setId((Integer) keys.get("film_id"));
        addGenre((Integer) keys.get("film_id"), film.getGenres());
        return film;
    }

    @Override
    public Film updateFilm(Film film) {
        getFilmById(film.getId());
        String sqlQuery = "UPDATE films "
                + "SET film_name = ?, "
                + "description = ?, "
                + "duration = ?, "
                + "release_date = ?, "
                + "rating_id = ? "
                + "WHERE film_id = ?";
        jdbcTemplate.update(sqlQuery, film.getName(), film.getDescription(), film.getDuration(),
                film.getReleaseDate(), film.getMpa().getId(), film.getId());
        addGenre(film.getId(), film.getGenres());
        int filmId = film.getId();
        film.setGenres(getGenres(filmId));
        return getFilmById(filmId);
    }

    @Override
    public Film getFilmById(int filmId) {
        String sqlQuery = "SELECT * FROM films "
                + "JOIN rating_mpa ON films.rating_id = rating_mpa.rating_id "
                + "WHERE film_id = ?";
        SqlRowSet srs = jdbcTemplate.queryForRowSet(sqlQuery, filmId);
        if (srs.next()) {
            return filmMap(srs);
        } else {
            throw new NotFoundException("Movie with ID = " + filmId + " not found");
        }
    }

    private void addGenre(int filmId, Set<Genre> genres) {
        deleteAllGenresById(filmId);
        if (genres == null || genres.isEmpty()) {
            return;
        }
        String sqlQuery = "INSERT INTO film_genres (film_id, genre_id) "
                + "VALUES (?, ?)";
        List<Genre> genresTable = new ArrayList<>(genres);
        this.jdbcTemplate.batchUpdate(sqlQuery, new BatchPreparedStatementSetter() {
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setInt(1, filmId);
                ps.setInt(2, genresTable.get(i).getId());
            }

            public int getBatchSize() {
                return genresTable.size();
            }
        });
    }

    private Set<Genre> getGenres(int filmId) {
        Set<Genre> result = new LinkedHashSet<>();
        String sqlQuery = "SELECT g.genre_id, g.genre_name FROM film_genres fg "
                + "JOIN genres g ON fg.genre_id = g.genre_id "
                + "WHERE fg.film_id = ? ORDER BY fg.genre_id ASC";

        jdbcTemplate.query(sqlQuery, rs -> {
            int genreId = rs.getInt("genre_id");
            String genreName = rs.getString("genre_name");

            if (genreName == null || genreName.isEmpty()) {
                throw new ValidationException("Genre with id=" + genreId + " has empty name");
            }

            result.add(new Genre(genreId, genreName));
        }, filmId);

        return result;
    }

    private void deleteAllGenresById(int filmId) {
        String sglQuery = "DELETE FROM film_genres WHERE film_id = ?";
        jdbcTemplate.update(sglQuery, filmId);
    }

    @Override
    public void addLike(int filmId, int userId) {
        String sqlQuery = "INSERT INTO likes (film_id, user_id) "
                + "VALUES (?, ?)";
        jdbcTemplate.update(sqlQuery, filmId, userId);
    }

    @Override
    public void removeLike(int filmId, int userId) {
        String sqlQuery = "DELETE likes "
                + "WHERE film_id = ? AND user_id = ?";
        jdbcTemplate.update(sqlQuery, filmId, userId);
    }

    @Override
    public List<Film> getMostPopularFilms(int count) {
        String sqlQuery = "SELECT films.film_id, films.film_name, films.description, films.duration, "
                + "films.release_date, films.rating_id, rating_mpa.rating_name "
                + "FROM films "
                + "LEFT JOIN likes ON likes.film_id = films.film_id "
                + "JOIN rating_mpa ON films.rating_id = rating_mpa.rating_id "
                + "GROUP BY films.film_id "
                + "ORDER BY COUNT(likes.film_id) DESC "
                + "LIMIT ?";

        return jdbcTemplate.query(sqlQuery, (rs, rowNum) -> {
            int filmId = rs.getInt("film_id");
            String name = rs.getString("film_name");
            String description = rs.getString("description");
            Long duration = rs.getLong("duration");

            LocalDate releaseDate = rs.getTimestamp("release_date") != null
                    ? rs.getTimestamp("release_date").toLocalDateTime().toLocalDate()
                    : null;

            int mpaId = rs.getInt("rating_id");
            String mpaName = rs.getString("rating_name");
            RatingMpa mpa = new RatingMpa(mpaId, mpaName);

            Set<Genre> genres = getGenres(filmId); // Получаем жанры для каждого фильма

            return buildFilm(filmId, name, description, duration, releaseDate, mpa, genres);
        }, count);
    }

    private List<Film> addGenreForList(List<Film> films) {
        Map<Integer, Film> filmsTable = films.stream().collect(Collectors.toMap(Film::getId, film -> film));
        String inSql = String.join(", ", Collections.nCopies(filmsTable.size(), "?"));
        final String sqlQuery = "SELECT * "
                + "FROM film_genres "
                + "LEFT OUTER JOIN genres ON film_genres.genre_id = genres.genre_id "
                + "WHERE film_genres.film_id IN (" + inSql + ") "
                + "ORDER BY film_genres.genre_id";
        jdbcTemplate.query(sqlQuery, (rs) -> {
            Film film = filmsTable.get(rs.getInt("film_id"));
            if (film != null) {
                film.addGenre(new Genre(rs.getInt("genre_id"), rs.getString("genre_name")));
            }
        }, filmsTable.keySet().toArray());
        return new ArrayList<>(filmsTable.values());
    }

    private Genre makeGenre(ResultSet rs, int id) throws SQLException {
        int genreId = rs.getInt("genre_id");
        String genreName = rs.getString("genre_name");
        return new Genre(genreId, genreName);
    }

    private Film makeFilm(ResultSet rs) throws SQLException {
        int filmId = rs.getInt("film_id");
        String name = rs.getString("film_name");
        String description = rs.getString("description");
        Long duration = rs.getLong("duration");

        LocalDate releaseDate = rs.getTimestamp("release_date") != null
                ? rs.getTimestamp("release_date").toLocalDateTime().toLocalDate()
                : null;

        int mpaId = rs.getInt("rating_id");
        String mpaName = rs.getString("rating_name");
        RatingMpa mpa = new RatingMpa(mpaId, mpaName);

        Set<Genre> genres = new HashSet<>();
        do {
            int genreId = rs.getInt("genre_id");
            String genreName = rs.getString("genre_name");
            if (genreId != 0 && genreName != null) {
                genres.add(new Genre(genreId, genreName));
            }
        } while (rs.next());

        return buildFilm(filmId, name, description, duration, releaseDate, mpa, genres);
    }

    private Film filmMap(SqlRowSet srs) {
        int filmId = srs.getInt("film_id");
        String name = srs.getString("film_name");
        String description = srs.getString("description");
        Long duration = srs.getLong("duration");

        LocalDate releaseDate = Objects.requireNonNull(srs.getTimestamp("release_date"))
                .toLocalDateTime().toLocalDate();

        int mpaId = srs.getInt("rating_id");
        String mpaName = srs.getString("rating_name");
        RatingMpa mpa = new RatingMpa(mpaId, mpaName);

        Set<Genre> genres = getGenres(filmId);

        return buildFilm(filmId, name, description, duration, releaseDate, mpa, genres);
    }

    private Film buildFilm(int filmId, String name, String description, Long duration,
                           LocalDate releaseDate, RatingMpa mpa, Set<Genre> genres) {
        return Film.builder()
                .id(filmId)
                .name(name)
                .description(description)
                .duration(duration)
                .releaseDate(releaseDate)
                .mpa(mpa)
                .genres(genres)
                .build();
    }
}