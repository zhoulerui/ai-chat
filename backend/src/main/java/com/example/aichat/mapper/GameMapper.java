package com.example.aichat.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.example.aichat.entity.Game;

@Mapper
public interface GameMapper {

    List<Game> selectGames();

    int insertGame(Game game);

    int deleteGame(@Param("id") Long id);

    int deleteChunksByGame(@Param("gameId") Long gameId);

    int deleteArticlesByGame(@Param("gameId") Long gameId);
}
